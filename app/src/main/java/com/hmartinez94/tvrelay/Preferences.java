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
    private static final String KEY_KODI_HOST = "kodi_host";
    private static final String KEY_KODI_PORT = "kodi_port";
    private static final String KEY_KODI_USER = "kodi_user";
    private static final String KEY_KODI_PASSWORD = "kodi_password";
    private static final String KEY_METADATA_PROVIDER = "metadata_provider";
    private static final String KEY_TMDB_API_KEY = "tmdb_api_key";

    private static final String DEFAULT_KODI_HOST = "127.0.0.1";
    private static final int DEFAULT_KODI_PORT = 8080;

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
            return PlayerApp.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return PlayerApp.NUVIO;
        }
    }

    public static void setSelectedApp(Context context, PlayerApp app) {
        prefs(context).edit().putString(KEY_PLAYER_APP, app.name()).apply();
    }

    public static String getKodiHost(Context context) {
        return prefs(context).getString(KEY_KODI_HOST, DEFAULT_KODI_HOST);
    }

    public static int getKodiPort(Context context) {
        return prefs(context).getInt(KEY_KODI_PORT, DEFAULT_KODI_PORT);
    }

    public static String getKodiUser(Context context) {
        return prefs(context).getString(KEY_KODI_USER, "");
    }

    public static String getKodiPassword(Context context) {
        return prefs(context).getString(KEY_KODI_PASSWORD, "");
    }

    public static void setKodiConnection(Context context, String host, int port, String user, String password) {
        prefs(context).edit()
                .putString(KEY_KODI_HOST, host)
                .putInt(KEY_KODI_PORT, port)
                .putString(KEY_KODI_USER, user)
                .putString(KEY_KODI_PASSWORD, password)
                .apply();
    }

    public static MetadataProvider getMetadataProvider(Context context) {
        String stored = prefs(context).getString(KEY_METADATA_PROVIDER, null);
        if (stored == null) {
            return MetadataProvider.THETVDB;
        }
        try {
            return MetadataProvider.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return MetadataProvider.THETVDB;
        }
    }

    public static void setMetadataProvider(Context context, MetadataProvider provider) {
        prefs(context).edit().putString(KEY_METADATA_PROVIDER, provider.name()).apply();
    }

    /** The user's own personal TMDB API key - only used when MetadataProvider.TMDB is selected. */
    public static String getTmdbApiKey(Context context) {
        return prefs(context).getString(KEY_TMDB_API_KEY, "");
    }

    public static void setTmdbApiKey(Context context, String apiKey) {
        prefs(context).edit().putString(KEY_TMDB_API_KEY, apiKey).apply();
    }
}
