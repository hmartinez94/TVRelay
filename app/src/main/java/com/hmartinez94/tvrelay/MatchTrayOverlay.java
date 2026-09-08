package com.hmartinez94.tvrelay;

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
 * Takes a Context, but which one matters: when built from the bound
 * accessibility service (the Google TV path), it can use
 * TYPE_ACCESSIBILITY_OVERLAY - which, unlike WatchNowOverlay's
 * TYPE_APPLICATION_OVERLAY, needs no "display over other apps" permission
 * at all - since that overlay type can only be added using a bound
 * service's own window token. Confirmed working on real hardware (ONN 4K
 * Pro). If adding it ever fails - a different device/OS version, or a
 * non-accessibility host like FireTvWatcherService (the Fire TV path, a
 * plain Service with no accessibility token) - this falls back to
 * TYPE_APPLICATION_OVERLAY when that permission is granted, and finally to
 * resolving and opening the top candidate directly with no chooser at all -
 * matching what happens when the chooser setting is off - rather than ever
 * showing a broken screen.
 */
final class MatchTrayOverlay {

    private static final String TAG = "MatchTrayOverlay";

    /**
     * How a shown tray ended, split three ways because the host needs to
     * react differently to each - see TvRelayAccessibilityService's
     * showChooser() for the one implementation.
     */
    interface Listener {
        /**
         * Every real dismissal of a currently-shown tray (cancel, Home,
         * picking a candidate) - never a no-op hide() call.
         * TvRelayAccessibilityService uses this to start a short cooldown
         * before trusting a subsequent window-state event as a fresh
         * voice-search landing - see its OVERLAY_TRANSITION_COOLDOWN_MS.
         * Confirmed real bug this fixes (2026-08-25): dismissing the tray
         * (Back) hands focus back to the launcher's EntityActivity, which
         * fires a lookalike window-state event with nothing to distinguish
         * it from a genuine voice-search landing.
         */
        void onDismissed();

        /**
         * The user backed out (BACK, or the Cancel button) without picking
         * anything - fired after onDismissed(), with the tray already gone.
         * NOT fired for a Home press (the user left the detail page - a
         * permanent-abandon signal that WatchNowOverlay's own Home receiver
         * already handles independently) or for a pick.
         */
        void onCancelled();

        /**
         * The user picked a candidate - fired after onDismissed(), with the
         * tray already gone and the launch about to start.
         */
        void onPicked(TitleCandidate candidate);
    }

    private final Context context;
    private final WindowManager windowManager;
    private final Listener listener;
    private View content;
    private BroadcastReceiver homeReceiver;

    MatchTrayOverlay(Context context, Listener listener) {
        this.context = context;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.listener = listener;
    }

    void show(String queryTitle, List<TitleCandidate> candidates) {
        hide();

        // Prefer TYPE_ACCESSIBILITY_OVERLAY (no "display over other apps"
        // permission needed) - works only when hosted by a bound
        // accessibility service (Google TV). On a non-a11y host
        // (FireTvWatcherService) that attempt fails and we fall back to
        // TYPE_APPLICATION_OVERLAY. Each attempt builds a FRESH view: a
        // failed addView can leave the View registered in WindowManagerGlobal
        // even though it threw, so reusing the same View for the second
        // attempt throws IllegalStateException("has already been added") -
        // confirmed real bug on the Fire TV watcher path (2026-09-07), which
        // silently dropped the chooser and auto-picked the top candidate.
        if (tryAddTray(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, queryTitle, candidates)) {
            Log.d(TAG, "Match tray shown via TYPE_ACCESSIBILITY_OVERLAY");
            return;
        }

        if (Settings.canDrawOverlays(context)) {
            int appOverlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            if (tryAddTray(appOverlayType, queryTitle, candidates)) {
                Log.d(TAG, "Match tray shown via TYPE_APPLICATION_OVERLAY");
                return;
            }
        }

        Log.w(TAG, "No usable overlay type - resolving top candidate automatically");
        handlePick(candidates.get(0));
    }

    /**
     * Builds a fresh tray view and tries to add it at the given window type.
     * Returns true on success (sets content, registers the Home receiver);
     * on failure logs, removes the view if it got partially registered (see
     * show()'s "already been added" note), and returns false so the caller
     * can try the next fallback with a clean slate.
     */
    private boolean tryAddTray(int windowType, String queryTitle, List<TitleCandidate> candidates) {
        View tray = MatchTrayView.build(context, queryTitle, candidates, this::handlePick, this::handleCancel);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.y = dp(48);
        try {
            windowManager.addView(tray, params);
            content = tray;
            registerHomeReceiver();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Overlay type " + windowType + " unavailable", e);
            try {
                windowManager.removeView(tray);
            } catch (Exception ignored) {
                // Wasn't fully added - nothing to remove.
            }
            return false;
        }
    }

    void hide() {
        if (homeReceiver != null) {
            try {
                context.unregisterReceiver(homeReceiver);
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
        listener.onDismissed();
    }

    /** Whether a tray is currently on screen - see Listener.onDismissed's javadoc for why a caller needs this. */
    boolean isShowing() {
        return content != null;
    }

    private void handleCancel() {
        boolean wasShowing = content != null;
        hide();
        if (wasShowing) {
            listener.onCancelled();
        }
    }

    private void handlePick(TitleCandidate candidate) {
        hide();
        listener.onPicked(candidate);
        new Thread(() -> PlayerLauncher.openCandidate(context, candidate)).start();
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
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
            context.registerReceiver(homeReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(homeReceiver, filter);
        }
    }
}
