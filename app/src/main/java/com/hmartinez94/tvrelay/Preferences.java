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

    private Preferences() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
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
     * movie/show card) gets redirected to SmartTube instead of being left
     * alone - see TvRelayAccessibilityService's YouTube-marker detection
     * and PlayerLauncher.prepareSmartTube(). Independent of getSelectedApp()
     * - this fires regardless of which movie/show player is selected.
     */
    public static boolean isSmartTubeEnabled(Context context) {
        return prefs(context).getBoolean(KEY_SMARTTUBE_ENABLED, true);
    }

    public static void setSmartTubeEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_SMARTTUBE_ENABLED, enabled).apply();
    }
}
