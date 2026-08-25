package com.hmartinez94.tvrelay;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.List;

/**
 * Floating host for MatchTrayView, added straight to WindowManager so the
 * chooser appears over whatever the launcher is showing with no screen
 * transition. This used to be one of two hosts A/B-tested against a
 * translucent Activity (MatchChooserActivity) - that Activity never
 * actually rendered as translucent (setContentView(View) forces its
 * argument to MATCH_PARENT regardless of the view's own size, so its
 * opaque tray background filled the whole screen) and was dropped
 * entirely rather than fixed, once this overlay proved to work better
 * anyway. See CLAUDE.md.
 *
 * Constructed from the bound accessibility service, not a plain Context:
 * TYPE_ACCESSIBILITY_OVERLAY - which, unlike WatchNowOverlay's
 * TYPE_APPLICATION_OVERLAY, needs no "display over other apps" permission
 * at all - can only be added using a bound service's own window token.
 * Confirmed working on real hardware (ONN 4K Pro). If adding it ever fails
 * (a different device/OS version, say), this falls back to
 * TYPE_APPLICATION_OVERLAY when that permission is granted, and finally to
 * resolving and opening the top candidate directly with no chooser at all -
 * matching what happens when the chooser setting is off - rather than ever
 * showing a broken screen.
 */
final class MatchTrayOverlay {

    private static final String TAG = "MatchTrayOverlay";

    private final AccessibilityService service;
    private final WindowManager windowManager;
    private final Runnable onDismissed;
    private View content;
    private BroadcastReceiver homeReceiver;

    MatchTrayOverlay(AccessibilityService service) {
        this(service, null);
    }

    /**
     * @param onDismissed Notified (if non-null) every time hide() actually
     *                     removes a currently-shown tray - i.e. real
     *                     dismissals only (Back/cancel, Home, picking a
     *                     candidate), not a no-op call. TvRelayAccessibilityService
     *                     uses this to start a short cooldown before treating
     *                     a subsequent window-state event as a fresh
     *                     voice-search landing - see its
     *                     OVERLAY_TRANSITION_COOLDOWN_MS. Confirmed real bug
     *                     this fixes (2026-08-25): dismissing the tray
     *                     (Back) hands focus back to the launcher's
     *                     EntityActivity, which fires a lookalike
     *                     window-state event with nothing to distinguish it
     *                     from a genuine voice-search landing.
     */
    MatchTrayOverlay(AccessibilityService service, Runnable onDismissed) {
        this.service = service;
        this.windowManager = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        this.onDismissed = onDismissed;
    }

    void show(String queryTitle, List<TitleCandidate> candidates) {
        hide();

        View tray = MatchTrayView.build(service, queryTitle, candidates, this::onPicked, this::hide);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.y = dp(48);

        try {
            windowManager.addView(tray, params);
            content = tray;
            registerHomeReceiver();
            Log.d(TAG, "Match tray shown via TYPE_ACCESSIBILITY_OVERLAY");
            return;
        } catch (Exception e) {
            Log.w(TAG, "TYPE_ACCESSIBILITY_OVERLAY unavailable, falling back", e);
        }

        if (Settings.canDrawOverlays(service)) {
            params.type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            try {
                windowManager.addView(tray, params);
                content = tray;
                registerHomeReceiver();
                Log.d(TAG, "Match tray shown via TYPE_APPLICATION_OVERLAY");
                return;
            } catch (Exception e) {
                Log.w(TAG, "TYPE_APPLICATION_OVERLAY unavailable too, resolving automatically instead", e);
            }
        }

        onPicked(candidates.get(0));
    }

    void hide() {
        if (homeReceiver != null) {
            try {
                service.unregisterReceiver(homeReceiver);
            } catch (Exception e) {
                // Already unregistered - nothing to do.
            }
            homeReceiver = null;
        }
        if (content == null) {
            return;
        }
        try {
            windowManager.removeView(content);
        } catch (Exception e) {
            Log.w(TAG, "Could not remove match tray overlay", e);
        }
        content = null;
        if (onDismissed != null) {
            onDismissed.run();
        }
    }

    /** Whether a tray is currently on screen - see onDismissed's javadoc for why a caller needs this. */
    boolean isShowing() {
        return content != null;
    }

    private void onPicked(TitleCandidate candidate) {
        hide();
        new Thread(() -> PlayerLauncher.openCandidate(service, candidate)).start();
    }

    private int dp(int value) {
        return Math.round(value * service.getResources().getDisplayMetrics().density);
    }

    /**
     * Same problem WatchNowOverlay solves the same way: pressing Home
     * doesn't deliver a normal KEYCODE_HOME to app windows - the system
     * intercepts it before this standalone window (not tied to any
     * Activity) ever sees it. Without this, the tray kept floating over
     * the Home screen after Home was pressed, and since it still held key
     * focus, the D-pad did nothing until the auto-dismiss.
     * ACTION_CLOSE_SYSTEM_DIALOGS is the standard (if informal) signal
     * other overlay apps use to detect this - broadcast by the system on
     * Home/Recents presses among other things.
     */
    private void registerHomeReceiver() {
        if (homeReceiver != null) {
            return;
        }
        homeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(TAG, "Dismissing match tray: ACTION_CLOSE_SYSTEM_DIALOGS ("
                        + intent.getStringExtra("reason") + ")");
                hide();
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        if (Build.VERSION.SDK_INT >= 33) {
            service.registerReceiver(homeReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            service.registerReceiver(homeReceiver, filter);
        }
    }
}
