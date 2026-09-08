package com.hmartinez94.tvrelay;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.Build;
import android.os.Process;

/**
 * Small helpers for the Fire TV detection path (see FireTvWatcherService /
 * FireTvModeStepFragment). Fire TV can't use the click-event pipeline the
 * rest of the app relies on - the current Fire TV home screen
 * (HomeActivity_vNext, the 2026 redesign) emits no AccessibilityEvents at
 * all to a sideloaded service (see CLAUDE.md's "Fire TV wall"). Instead,
 * "Fire TV mode" watches UsageStats for the launcher's own in-app detail
 * page opening and reads the title off the screen with the existing
 * MediaProjection + OCR pipeline - the same approach the reference app
 * (Yushetf33/TvReccomendationBridge) documents using, and confirmed working
 * on a real Fire TV Stick 2026-09-07.
 */
final class FireTvSupport {

    private FireTvSupport() {
    }

    /**
     * Whether this device is an Amazon Fire TV. Checked so "Fire TV mode" is
     * only offered where it's relevant - the whole mechanism (UsageStats +
     * OCR instead of accessibility clicks) exists solely because Fire OS's
     * launcher behaves differently; on Google TV the normal click path works
     * and this mode would just be a worse, permission-heavier duplicate.
     * Uses the Fire-TV system feature (confirmed present as
     * "amazon.hardware.fire_tv" on the test Stick), with the manufacturer
     * string as a secondary signal.
     */
    static boolean isFireTv(Context context) {
        if (context.getPackageManager().hasSystemFeature("amazon.hardware.fire_tv")) {
            return true;
        }
        return "Amazon".equalsIgnoreCase(Build.MANUFACTURER);
    }

    /**
     * Whether TVRelay currently holds Usage Access (the PACKAGE_USAGE_STATS
     * special-access grant) - required for FireTvWatcherService's UsageStats
     * polling to return anything. There's no direct permission-check API for
     * this AppOps-gated grant; the documented way is checkOp against
     * OPSTR_GET_USAGE_STATS for our own uid/package. Returns false on any
     * error rather than throwing - a false "not granted" just re-shows the
     * grant prompt, which is harmless.
     */
    static boolean hasUsageAccess(Context context) {
        try {
            AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) {
                return false;
            }
            int mode;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(), context.getPackageName());
            } else {
                mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(), context.getPackageName());
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }
}
