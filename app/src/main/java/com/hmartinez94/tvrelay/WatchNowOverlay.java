package com.hmartinez94.tvrelay;

import android.content.Context;
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

    /** Swaps to the real confirm state once TheTVDB resolution completes. */
    void showConfirm(TvdbMatch match, PlayerApp app, Runnable onConfirm) {
        ensureButton();
        if (button == null) {
            return;
        }
        Log.d(TAG, "Showing confirm for " + match + " via " + app.getLabel());
        button.setText(appContext.getString(R.string.watch_now_confirm, app.getLabel()));
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
        } catch (Exception e) {
            Log.e(TAG, "Could not show Watch Now overlay", e);
        }
    }
}
