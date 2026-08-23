package com.hmartinez94.tvrelay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.Button;

/**
 * Floating confirmation button shown over whatever screen the launcher
 * navigates to after a recognized click. Two states:
 *  - loading: shown immediately once a click is recognized, before
 *    TheTVDB resolution completes - so the button appears "as soon as
 *    possible" rather than only once the network call finishes.
 *  - confirm: "Watch now in {App}" once resolved. Tapping it - and only
 *    tapping it - launches the title. Auto-dismisses if ignored, which is
 *    deliberate: that's the accidental-click protection this feature
 *    exists for in the first place.
 *
 * Requires the "Display over other apps" permission (SYSTEM_ALERT_WINDOW).
 * Callers must check isPermissionGranted() themselves and fall back to
 * launching directly when it's false - see TvRelayAccessibilityService.
 */
final class WatchNowOverlay {

    private static final String TAG = "WatchNowOverlay";
    private static final long AUTO_DISMISS_MS = 10_000;

    private final Context appContext;
    private final WindowManager windowManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Button button;
    private Runnable autoDismiss;
    private BroadcastReceiver homeReceiver;

    WatchNowOverlay(Context context) {
        this.appContext = context.getApplicationContext();
        this.windowManager = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
    }

    boolean isPermissionGranted() {
        return Settings.canDrawOverlays(appContext);
    }

    /** Shown the instant a click is recognized, before resolution finishes. */
    void showLoading() {
        ensureButton();
        if (button == null) {
            return;
        }
        button.setText(R.string.watch_now_loading);
        button.setOnClickListener(null);
        resetAutoDismiss();
    }

    /** Swaps to the real confirm state once resolution completes (or, for the Nuvio+TMDB fast path, needed no resolution at all - see PlayerLauncher.prepare). */
    void showConfirm(PlayerApp app, Runnable onConfirm) {
        Log.d(TAG, "Showing confirm via " + app.getLabel());
        showConfirmWithText(appContext.getString(R.string.watch_now_confirm, app.getLabel()), onConfirm);
    }

    /**
     * Same confirm step, but for a title that turned out to have more than
     * one exact match (see MetadataResolver.isAmbiguous) - there's no
     * single resolved match yet to name in the button, so "onConfirm" is
     * expected to show the chooser rather than launch anything directly.
     */
    void showConfirmAmbiguous(PlayerApp app, Runnable onConfirm) {
        Log.d(TAG, "Showing ambiguous-match confirm via " + app.getLabel());
        showConfirmWithText(appContext.getString(R.string.watch_now_choose, app.getLabel()), onConfirm);
    }

    private void showConfirmWithText(String text, Runnable onConfirm) {
        ensureButton();
        if (button == null) {
            return;
        }
        button.setText(text);
        button.setOnClickListener(v -> {
            hide();
            onConfirm.run();
        });
        button.requestFocus();
        resetAutoDismiss();
    }

    void hide() {
        if (autoDismiss != null) {
            mainHandler.removeCallbacks(autoDismiss);
            autoDismiss = null;
        }
        if (homeReceiver != null) {
            try {
                appContext.unregisterReceiver(homeReceiver);
            } catch (Exception e) {
                // Already unregistered - nothing to do.
            }
            homeReceiver = null;
        }
        if (button == null) {
            return;
        }
        try {
            windowManager.removeView(button);
        } catch (Exception e) {
            Log.e(TAG, "Could not remove Watch Now overlay", e);
        }
        button = null;
    }

    private void resetAutoDismiss() {
        if (autoDismiss != null) {
            mainHandler.removeCallbacks(autoDismiss);
        }
        autoDismiss = this::hide;
        mainHandler.postDelayed(autoDismiss, AUTO_DISMISS_MS);
    }

    private void ensureButton() {
        if (button != null) {
            return;
        }
        if (!isPermissionGranted()) {
            return;
        }

        Button newButton = new Button(appContext);
        // Deliberately not FLAG_NOT_FOCUSABLE: the button needs to be able
        // to take D-pad focus to be usable at all on a TV. That means it
        // becomes the focused window the moment it's shown, which is
        // exactly why Back needs explicit handling here - without it
        // there would be no way to dismiss the overlay and hand control
        // back to whatever's underneath.
        newButton.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                hide();
                return true;
            }
            // A D-pad press means the user is trying to navigate, not
            // confirm - get out of the way immediately instead of eating
            // the input. This button is the only focusable view in its
            // window, so without this the press did nothing at all
            // (confirmed on-device: user stuck unable to move until the
            // 10s auto-dismiss). React on ACTION_DOWN so the very first
            // press dismisses it, not just the eventual key-up.
            // KEYCODE_DPAD_CENTER/ENTER are deliberately excluded - those
            // are the confirm click itself and must reach the button.
            if (isDirectionalKey(keyCode) && event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() == 0) {
                Log.d(TAG, "Dismissing overlay: directional key " + keyCode);
                hide();
                return true;
            }
            return false;
        });

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.END;
        params.x = 48;
        params.y = 48;

        try {
            windowManager.addView(newButton, params);
            button = newButton;
            registerHomeReceiver();
        } catch (Exception e) {
            Log.e(TAG, "Could not show Watch Now overlay", e);
        }
    }

    private static boolean isDirectionalKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    /**
     * Pressing Home doesn't deliver a normal KEYCODE_HOME to app windows -
     * the system intercepts it before any app sees it, so it can't be
     * caught in the button's OnKeyListener above. This overlay is a
     * standalone system window (TYPE_APPLICATION_OVERLAY/TYPE_PHONE), not
     * tied to any Activity, so without this it kept floating on top of the
     * Home screen after Home was pressed - and since it still held key
     * focus, the user couldn't move the D-pad at all until the 10s
     * auto-dismiss. Confirmed real bug on-device.
     *
     * ACTION_CLOSE_SYSTEM_DIALOGS is the standard (if informal) signal
     * other overlay apps use to detect this - it's broadcast by the system
     * on Home/Recents presses among other things. Only *sending* it is
     * restricted on API 31+; apps can still register to receive it.
     */
    private void registerHomeReceiver() {
        if (homeReceiver != null) {
            return;
        }
        homeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(TAG, "Dismissing overlay: ACTION_CLOSE_SYSTEM_DIALOGS ("
                        + intent.getStringExtra("reason") + ")");
                hide();
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(homeReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            appContext.registerReceiver(homeReceiver, filter);
        }
    }
}
