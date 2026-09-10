package com.hmartinez94.tvrelay;

import android.app.LocaleManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;
import android.util.Log;

import androidx.annotation.RequiresApi;

/**
 * Resolves a {@link Resources} scoped to the real SYSTEM locale, for the
 * launcher card-shape markers in res/values/arrays.xml.
 *
 * Why this exists rather than just calling context.getResources(): those
 * markers are matched against text the LAUNCHER renders, so they have to
 * follow the system/launcher language, not TVRelay's own UI language. An
 * Android 13+ per-app language override (Settings > Apps > Language, or
 * LocaleManager.setApplicationLocales) changes the latter without changing
 * the former, and Locale.getDefault() / context.getResources() both follow
 * the override. LocaleManager.getSystemLocales() exists specifically to
 * ignore it. Below API 33 there is no OS-level per-app override to ignore,
 * so the app's own configured locales already ARE the system locales.
 *
 * Deliberately different from TmdbClient's Locale.getDefault()-based
 * language code, which is correct for its own purpose (which language the
 * USER wants metadata in, where a per-app override SHOULD win) - do not
 * unify the two.
 *
 * Static, Context per call, no caching - same shape as Preferences and
 * OcrCaptureConfig.cropFor(). Resolved fresh every time on purpose:
 * detection runs at most a few times a minute (on user clicks), a cached
 * Resources would go stale after a runtime language change, and a static
 * cache would leak a Context. ResourcesManager already caches the
 * underlying ResourcesImpl per configuration, so repeated calls with an
 * unchanged locale are cheap.
 *
 * Never throws: every failure path falls back to the calling Context's own
 * Resources. Callers are on the AccessibilityService main thread, where an
 * uncaught exception would take the whole service down.
 */
final class LauncherLocale {

    private static final String TAG = "LauncherLocale";

    private LauncherLocale() {
    }

    /**
     * Resources for the current system locale list. Use only for the
     * launcher-facing markers in arrays.xml - everything user-facing must
     * keep using the ordinary context.getString()/getResources().
     */
    static Resources systemResources(Context context) {
        try {
            LocaleList systemLocales = systemLocales(context);
            if (systemLocales == null || systemLocales.isEmpty()) {
                return context.getResources();
            }
            // A COPY - mutating the Configuration returned by
            // getResources().getConfiguration() would corrupt the app's own.
            Configuration override = new Configuration(context.getResources().getConfiguration());
            // setLocales(), not setLocale(): passing the whole list keeps
            // Android's normal multi-locale resource fallback, which is the
            // same walk the launcher itself does for its own strings.
            override.setLocales(systemLocales);
            return context.createConfigurationContext(override).getResources();
        } catch (Exception e) {
            // Fail open. A wrong-locale marker set only costs a missed
            // detection; an exception here would kill the service.
            Log.w(TAG, "Could not resolve system-locale Resources - using this app's own", e);
            return context.getResources();
        }
    }

    private static LocaleList systemLocales(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The SDK_INT check has to be inline here, not only inside the
            // helper: lint's NewApi detector does not infer safety across a
            // separate helper method's own internal check (same reason as
            // UpdateStepFragment's ApkInstaller call).
            LocaleList fromLocaleManager = systemLocalesTiramisu(context);
            if (fromLocaleManager != null && !fromLocaleManager.isEmpty()) {
                return fromLocaleManager;
            }
        }
        // Below API 33 there is no OS-level per-app language override, so
        // the app's own configuration already carries the system locales.
        // Configuration.getLocales() is API 24 = this project's minSdk, so
        // the deprecated Configuration.locale field is never needed.
        return context.getResources().getConfiguration().getLocales();
    }

    /**
     * Isolated in its own method so the API 33-only LocaleManager class
     * reference is never resolved at all on older devices - same pattern as
     * ApkInstaller's own version-gated install-permission request.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private static LocaleList systemLocalesTiramisu(Context context) {
        LocaleManager localeManager = context.getSystemService(LocaleManager.class);
        return localeManager != null ? localeManager.getSystemLocales() : null;
    }
}
