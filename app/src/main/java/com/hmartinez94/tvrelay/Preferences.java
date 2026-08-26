package com.hmartinez94.tvrelay;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Thin SharedPreferences wrapper shared between the settings UI (where
 * values are set) and the accessibility service (where they're read).
 */
public final class Preferences {

    private static final String PREFS_NAME = "tvrelay_prefs";

    private static final String KEY_DISCLOSURE_ACCEPTED = "disclosure_accepted";
    private static final String KEY_PLAYER_APP = "player_app";
    private static final String KEY_METADATA_PROVIDER = "metadata_provider";
    private static final String KEY_TMDB_API_KEY = "tmdb_api_key";
    private static final String KEY_SHOW_CHOOSER = "show_match_chooser";
    private static final String KEY_SMARTTUBE_ENABLED = "smarttube_redirect_enabled";
    private static final String KEY_OCR_FALLBACK_ENABLED = "ocr_fallback_enabled";
    private static final String KEY_OCR_DISCLOSURE_ACCEPTED = "ocr_disclosure_accepted";
    private static final String KEY_ACCESSIBILITY_ENABLE_CLICKED_AT = "accessibility_enable_clicked_at";
    private static final String KEY_ACCESSIBILITY_SERVICE_EVER_CONNECTED = "accessibility_service_ever_connected";
    private static final String KEY_YOUTUBE_REDIRECT_TARGET = "youtube_redirect_target";
    private static final String KEY_OVERLAY_REAPPEAR_ENABLED = "overlay_reappear_enabled";
    private static final String KEY_UPDATE_CHECKED_AT = "update_checked_at";
    private static final String KEY_UPDATE_LATEST_VERSION = "update_latest_version";
    private static final String KEY_UPDATE_APK_URL = "update_apk_url";

    private Preferences() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * The underlying SharedPreferences instance, for a caller (currently
     * only TvRelayAccessibilityService) that needs to observe changes via
     * OnSharedPreferenceChangeListener rather than polling one getter -
     * see its live-conditional OCR eventTypes wiring. Package-private:
     * every other caller should keep going through the named getters/
     * setters above instead of reading/writing keys directly.
     */
    static SharedPreferences rawPrefs(Context context) {
        return prefs(context);
    }

    public static boolean isDisclosureAccepted(Context context) {
        return prefs(context).getBoolean(KEY_DISCLOSURE_ACCEPTED, false);
    }

    public static void setDisclosureAccepted(Context context, boolean accepted) {
        prefs(context).edit().putBoolean(KEY_DISCLOSURE_ACCEPTED, accepted).apply();
    }

    public static PlayerApp getSelectedApp(Context context) {
        String stored = prefs(context).getString(KEY_PLAYER_APP, null);
        if (stored == null) {
            return PlayerApp.NUVIO;
        }
        try {
            PlayerApp app = PlayerApp.valueOf(stored);
            // Covers a player disabled after being saved (e.g. Plex, on a
            // device that had it selected before it was disabled - see
            // PlayerApp.isEnabled()/CLAUDE.md) - falls back the same way an
            // unrecognized stored value already did below.
            return app.isEnabled() ? app : PlayerApp.NUVIO;
        } catch (IllegalArgumentException e) {
            return PlayerApp.NUVIO;
        }
    }

    public static void setSelectedApp(Context context, PlayerApp app) {
        prefs(context).edit().putString(KEY_PLAYER_APP, app.name()).apply();
    }

    public static MetadataProvider getMetadataProvider(Context context) {
        String stored = prefs(context).getString(KEY_METADATA_PROVIDER, null);
        if (stored == null) {
            return MetadataProvider.TMDB;
        }
        try {
            return MetadataProvider.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return MetadataProvider.TMDB;
        }
    }

    public static void setMetadataProvider(Context context, MetadataProvider provider) {
        prefs(context).edit().putString(KEY_METADATA_PROVIDER, provider.name()).apply();
    }

    /**
     * The user's own personal TMDB API key, exactly as typed into Settings -
     * empty if they haven't entered one. Used only for the editable Settings
     * field itself; actual lookups go through getEffectiveTmdbApiKey()
     * instead, so this stays blank (rather than showing the bundled default
     * pre-filled) until the user deliberately overrides it.
     */
    public static String getTmdbApiKey(Context context) {
        return prefs(context).getString(KEY_TMDB_API_KEY, "");
    }

    public static void setTmdbApiKey(Context context, String apiKey) {
        prefs(context).edit().putString(KEY_TMDB_API_KEY, apiKey).apply();
    }

    /**
     * The key actual TMDB lookups should use: the user's own, if they've set
     * one, otherwise the app's bundled default (BuildConfig.TMDB_API_KEY,
     * from TMDB_API_KEY in local.properties - see TvdbClient's TVDB_API_KEY
     * for the same pattern). A user's own key is still fully supported and
     * takes priority - e.g. if the shared default ever gets rate-limited.
     */
    public static String getEffectiveTmdbApiKey(Context context) {
        String own = getTmdbApiKey(context).trim();
        return !own.isEmpty() ? own : BuildConfig.TMDB_API_KEY.trim();
    }

    /** Whether an ambiguous title match shows a "which one did you mean?" chooser instead of guessing. */
    public static boolean isChooserEnabled(Context context) {
        return prefs(context).getBoolean(KEY_SHOW_CHOOSER, true);
    }

    public static void setChooserEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_SHOW_CHOOSER, enabled).apply();
    }

    /**
     * Whether a YouTube video recommendation card (as opposed to a
     * movie/show card) gets redirected to a sideloaded YouTube TV client
     * instead of being left alone - which client is a separate, explicit
     * choice (see getYouTubeRedirectTarget() below), not auto-detected.
     * See TvRelayAccessibilityService's YouTube-marker detection and
     * PlayerLauncher.prepareYouTubeRedirect(). Independent of
     * getSelectedApp() - this fires regardless of which movie/show player is
     * selected. Kept as "SmartTube" in the method/preference-key name for
     * minimal churn (this predates TizenTube Cobalt support and nothing
     * depends on renaming it) - only the user-facing strings and this
     * comment describe the broadened, multi-target behavior.
     */
    public static boolean isSmartTubeEnabled(Context context) {
        return prefs(context).getBoolean(KEY_SMARTTUBE_ENABLED, true);
    }

    public static void setSmartTubeEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_SMARTTUBE_ENABLED, enabled).apply();
    }

    /**
     * Which sideloaded YouTube TV client the toggle above targets when it's
     * on - see YouTubeRedirectTarget and PlayerLauncher.prepareYouTubeRedirect().
     * An explicit Settings dropdown, not auto-detected install-state
     * guessing (the original 2026-08-24 design - see CLAUDE.md's "SmartTube
     * redirect" - replaced 2026-08-25 by user request, since a user with
     * both apps installed had no way to actually pick TizenTube Cobalt over
     * the auto-preferred SmartTube).
     */
    public static YouTubeRedirectTarget getYouTubeRedirectTarget(Context context) {
        String stored = prefs(context).getString(KEY_YOUTUBE_REDIRECT_TARGET, null);
        if (stored == null) {
            return YouTubeRedirectTarget.SMARTTUBE;
        }
        try {
            return YouTubeRedirectTarget.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return YouTubeRedirectTarget.SMARTTUBE;
        }
    }

    public static void setYouTubeRedirectTarget(Context context, YouTubeRedirectTarget target) {
        prefs(context).edit().putString(KEY_YOUTUBE_REDIRECT_TARGET, target.name()).apply();
    }

    /**
     * Whether the screen-reading (MediaProjection + on-device OCR) fallback
     * is armed - see OcrCaptureManager and TvRelayAccessibilityService's
     * two trigger points (an empty click payload, and a voice-search result
     * with no title - see CLAUDE.md's "capabilities wall" and "voice search
     * wall"). Deliberately gated behind its own disclosure flag
     * (isOcrDisclosureAccepted), separate from the base
     * isDisclosureAccepted() - MediaProjection is a materially bigger,
     * more visible grant (a persistent system recording indicator) than
     * the base accessibility-only disclosure, so it gets its own explicit
     * opt-in screen rather than riding along with the first one.
     */
    public static boolean isOcrFallbackEnabled(Context context) {
        return prefs(context).getBoolean(KEY_OCR_FALLBACK_ENABLED, false);
    }

    public static void setOcrFallbackEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_OCR_FALLBACK_ENABLED, enabled).apply();
    }

    /** Whether the user has accepted OcrDisclosureStepFragment's separate MediaProjection consent screen. */
    public static boolean isOcrDisclosureAccepted(Context context) {
        return prefs(context).getBoolean(KEY_OCR_DISCLOSURE_ACCEPTED, false);
    }

    public static void setOcrDisclosureAccepted(Context context, boolean accepted) {
        prefs(context).edit().putBoolean(KEY_OCR_DISCLOSURE_ACCEPTED, accepted).apply();
    }

    /**
     * Timestamp (millis, System.currentTimeMillis()) of the last time the
     * user tapped "Enable in Accessibility settings" - 0 if never. One of two
     * signals SettingsStepFragment combines to decide whether to offer the
     * Restricted Settings walkthrough - see
     * SettingsStepFragment.shouldOfferRestrictedSettingsHelp().
     */
    public static long getAccessibilityEnableClickedAt(Context context) {
        return prefs(context).getLong(KEY_ACCESSIBILITY_ENABLE_CLICKED_AT, 0L);
    }

    public static void setAccessibilityEnableClickedAt(Context context, long timestampMillis) {
        prefs(context).edit().putLong(KEY_ACCESSIBILITY_ENABLE_CLICKED_AT, timestampMillis).apply();
    }

    /**
     * Whether TvRelayAccessibilityService.onServiceConnected() has ever fired
     * - i.e. the OS actually bound the service at least once, a stronger
     * signal than Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES merely
     * listing the component (which only reflects whether the OS accepted the
     * enable request, not whether the service actually bound). Used to avoid
     * a false positive: if the service connected before, a later "not
     * enabled" reading means it was turned off deliberately or crashed - not
     * Android's Restricted Settings block, which only ever prevents the
     * *first* successful enable.
     */
    public static boolean hasAccessibilityServiceEverConnected(Context context) {
        return prefs(context).getBoolean(KEY_ACCESSIBILITY_SERVICE_EVER_CONNECTED, false);
    }

    public static void setAccessibilityServiceEverConnected(Context context, boolean connected) {
        prefs(context).edit().putBoolean(KEY_ACCESSIBILITY_SERVICE_EVER_CONNECTED, connected).apply();
    }

    /**
     * Whether WatchNowOverlay.hide() (a Back/D-pad dismiss) conceals the
     * confirm button and lets it reappear a few seconds later - see
     * WatchNowOverlay's class doc - or, when this is off, discards the
     * pending match outright, matching this app's original (pre-2026-08-25)
     * behavior where any dismissal was permanent. Default on.
     */
    public static boolean isOverlayReappearEnabled(Context context) {
        return prefs(context).getBoolean(KEY_OVERLAY_REAPPEAR_ENABLED, true);
    }

    public static void setOverlayReappearEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_OVERLAY_REAPPEAR_ENABLED, enabled).apply();
    }

    /**
     * Timestamp (millis) of the last time GithubReleaseClient was actually
     * queried for a newer release - 0 if never. Throttles that network call
     * (see SettingsStepFragment) so opening Settings repeatedly doesn't hit
     * the GitHub API every time; same timestamp-throttle shape as
     * getAccessibilityEnableClickedAt() above.
     */
    public static long getUpdateCheckedAt(Context context) {
        return prefs(context).getLong(KEY_UPDATE_CHECKED_AT, 0L);
    }

    public static void setUpdateCheckedAt(Context context, long timestampMillis) {
        prefs(context).edit().putLong(KEY_UPDATE_CHECKED_AT, timestampMillis).apply();
    }

    /**
     * The newest version GithubReleaseClient has seen on GitHub so far -
     * empty if no check has ever found one newer than what's installed.
     * Cached (rather than re-derived from a fresh network call every time)
     * so the "Update available" Settings row can render immediately from the
     * last known result while the next throttled check runs in the
     * background - see UpdateStepFragment/getUpdateApkUrl().
     */
    public static String getUpdateLatestVersion(Context context) {
        return prefs(context).getString(KEY_UPDATE_LATEST_VERSION, "");
    }

    public static String getUpdateApkUrl(Context context) {
        return prefs(context).getString(KEY_UPDATE_APK_URL, "");
    }

    public static void setUpdateAvailable(Context context, String version, String apkUrl) {
        prefs(context).edit()
                .putString(KEY_UPDATE_LATEST_VERSION, version)
                .putString(KEY_UPDATE_APK_URL, apkUrl)
                .apply();
    }
}
