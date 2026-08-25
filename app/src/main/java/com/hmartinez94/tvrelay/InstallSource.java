package com.hmartinez94.tvrelay;

import android.content.Context;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageManager;
import android.os.Build;

/** Detects whether TVRelay's own installed copy came from the Play Store. */
final class InstallSource {

    private static final String PLAY_STORE_PACKAGE = "com.android.vending";

    private InstallSource() {
    }

    /**
     * Whether TVRelay's own installed copy came from the Play Store - the
     * one trusted installer that is never subject to Android 13+'s
     * Restricted Settings block (see RestrictedSettingsStepFragment /
     * CLAUDE.md). On any ambiguity (exception, null installer - i.e. a
     * sideloaded or otherwise-unknown install) this deliberately returns
     * false: a false "show the help row" just costs one unused Settings
     * entry, while a false "hide it" would strand a genuinely stuck
     * sideloaded user with zero in-app help.
     */
    static boolean isPlayStoreInstall(Context context) {
        return PLAY_STORE_PACKAGE.equals(getInstallingPackageName(context));
    }

    private static String getInstallingPackageName(Context context) {
        PackageManager pm = context.getPackageManager();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                InstallSourceInfo info = pm.getInstallSourceInfo(context.getPackageName());
                return info.getInstallingPackageName();
            }
            @SuppressWarnings("deprecation")
            String installer = pm.getInstallerPackageName(context.getPackageName());
            return installer;
        } catch (PackageManager.NameNotFoundException | IllegalArgumentException e) {
            return null;
        }
    }
}
