package com.hmartinez94.tvrelay;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;

import javax.net.ssl.SSLException;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Jellyfin client for PlayerLauncher.planTitleSearch()'s opt-in "find it in
 * the user's own library first" path - see Preferences.
 * isJellyfinLibraryLookupReady() and JellyfinSettingsStepFragment. When a hit
 * is found, TVRelay opens the title directly, the same way it does for
 * Nuvio/Stremio/WuPlay - see CLAUDE.md's "Plex and Jellyfin" section for the
 * full history (this used to be a search-only hand-off for both players;
 * Jellyfin is the one that grew a direct-open path).
 *
 * Deliberately title-search only, never provider-id/IMDb-based:
 * Jellyfin's /Items endpoint has no provider-id filter exposed at all.
 * `anyProviderIdEquals` is an Emby-only parameter - jellyfin/jellyfin#1990
 * was closed by a maintainer as "Jellyfin is not Emby, so this is not a bug
 * but a feature request", and the string appears nowhere in Jellyfin's
 * current server source (the internal InternalItemsQuery.HasAnyProviderId
 * filter exists, but ItemsController.GetItems never exposes it as a query
 * param). Don't re-attempt a provider-id lookup without new evidence this
 * has changed upstream - matching has to stay title-based, same exact-match
 * risk TMDB/TheTVDB already have (see ExactMatchPicker), reused here as-is.
 *
 * Auth is a static per-request header, not a login flow like TvdbClient's -
 * an API key generated once in Jellyfin's own Dashboard needs no session at
 * all. Confirmed against Jellyfin's own AuthorizationContext source: an
 * API-key request "can always access all folders", no userId needed.
 */
final class JellyfinClient {

    private static final String TAG = "JellyfinClient";
    private static final OkHttpClient httpClient = new OkHttpClient();

    private JellyfinClient() {
    }

    /**
     * Every plausible match for a title from the user's own Jellyfin
     * library, most-likely first - see ExactMatchPicker.ranked(). Empty
     * (never null) if library lookup isn't turned on/fully configured, or
     * nothing usable came back. Blocking network call - call only from a
     * background thread.
     */
    static List<TitleCandidate> findLibraryCandidates(Context context, String title) {
        if (!Preferences.isJellyfinLibraryLookupReady(context)) {
            return Collections.emptyList();
        }
        String baseUrl = normalizeBaseUrl(Preferences.getJellyfinUrl(context));
        String apiKey = Preferences.getJellyfinApiKey(context).trim();
        String deviceId = deviceId(context);
        return TitleSearchFallbacks.resolve(title, t -> search(baseUrl, apiKey, deviceId, t));
    }

    private static List<TitleCandidate> search(String baseUrl, String apiKey, String deviceId, String title) {
        try {
            return doSearch(baseUrl, apiKey, deviceId, title);
        } catch (Exception e) {
            Log.e(TAG, "Jellyfin library search failed for: " + title, e);
            return Collections.emptyList();
        }
    }

    private static List<TitleCandidate> doSearch(String baseUrl, String apiKey, String deviceId, String title)
            throws IOException, JSONException {
        String url = baseUrl + "/Items?recursive=true&includeItemTypes=Movie,Series"
                + "&searchTerm=" + URLEncoder.encode(title, "UTF-8")
                + "&limit=25&fields=ProductionYear&enableImages=false&enableTotalRecordCount=false";
        Request request = new Request.Builder().url(url)
                .header("Authorization", authHeader(apiKey, deviceId))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                Log.w(TAG, "Jellyfin /Items failed: " + response.code());
                return Collections.emptyList();
            }
            String body = response.body() != null ? response.body().string() : null;
            if (body == null) {
                return Collections.emptyList();
            }
            JSONObject json = new JSONObject(body);
            JSONArray items = json.optJSONArray("Items");
            if (items == null) {
                return Collections.emptyList();
            }

            String normalizedQuery = ExactMatchPicker.normalize(title);
            ExactMatchPicker<TitleCandidate> picker = new ExactMatchPicker<>();

            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                String jellyfinType = item.optString("Type", "");
                if (!"Movie".equals(jellyfinType) && !"Series".equals(jellyfinType)) {
                    continue;
                }
                String name = item.optString("Name", "");
                String id = item.optString("Id", "");
                if (name.isEmpty() || id.isEmpty()) {
                    continue;
                }
                MediaType mediaType = "Movie".equals(jellyfinType) ? MediaType.MOVIE : MediaType.SERIES;
                boolean isExactMatch = normalizedQuery.equals(ExactMatchPicker.normalize(name));
                int year = !item.isNull("ProductionYear")
                        ? item.optInt("ProductionYear", Integer.MIN_VALUE)
                        : Integer.MIN_VALUE;
                TitleCandidate candidate = TitleCandidate.fromJellyfin(name, year, mediaType, isExactMatch, id);
                picker.offer(candidate, isExactMatch, year);
            }

            List<TitleCandidate> ranked = picker.ranked();
            if (!ranked.isEmpty() && !picker.hasExactMatch()) {
                // Deliberately NOT what gets returned to a caller as "found
                // in your library" - planTitleSearch() only trusts an exact
                // match (see its javadoc); a relevance-only hit here would
                // misidentify the click as a library match for the wrong
                // title. Logged only so a real case is visible if this ever
                // needs revisiting - see TmdbClient's identical logging.
                Log.d(TAG, "No exact library match for \"" + title + "\" - not treating as found");
            }
            return ranked;
        }
    }

    /**
     * Result of testConnection() - exactly one of serverName/errorDetail is
     * non-null. errorDetail is a short, human-readable reason (not a raw
     * exception message except as a last resort) so a non-developer user can
     * tell a bad URL apart from a bad key without needing logcat - see
     * testConnection()'s javadoc for why this matters more here than for
     * TmdbClient.testApiKey()'s plain boolean.
     */
    static final class ConnectionResult {
        final String serverName;
        final String errorDetail;

        private ConnectionResult(String serverName, String errorDetail) {
            this.serverName = serverName;
            this.errorDetail = errorDetail;
        }

        static ConnectionResult success(String serverName) {
            return new ConnectionResult(serverName, null);
        }

        static ConnectionResult failure(String errorDetail) {
            return new ConnectionResult(null, errorDetail);
        }

        boolean isSuccess() {
            return serverName != null;
        }
    }

    /**
     * Reachability/key-validity check for the Settings "Test connection"
     * action - hits /System/Info ([Authorize(Policy =
     * FirstTimeSetupOrIgnoreParentalControl)], satisfied by any valid API
     * key - confirmed against Jellyfin's own SystemController source) and
     * returns the server's own name on success (so the caller can show
     * which server actually answered).
     *
     * Distinguishes failure reasons rather than returning a plain
     * null/false, unlike TmdbClient.testApiKey() - confirmed real gap
     * (2026-08-27): a user reported "the API key is not working" with no
     * way for either of us to tell whether that meant a typo'd key (401), a
     * wrong/unreachable URL (DNS/connect failure), or an HTTPS server with
     * no valid certificate - all three look identical as a plain toast.
     */
    static ConnectionResult testConnection(Context context, String rawBaseUrl, String rawApiKey) {
        String baseUrl = normalizeBaseUrl(rawBaseUrl);
        String apiKey = rawApiKey == null ? "" : rawApiKey.trim();
        if (baseUrl.isEmpty()) {
            return ConnectionResult.failure("Enter a server URL");
        }
        if (apiKey.isEmpty()) {
            return ConnectionResult.failure("Enter an API key");
        }
        Log.d(TAG, "Testing connection: " + baseUrl + "/System/Info");
        try {
            String url = baseUrl + "/System/Info";
            Request request = new Request.Builder().url(url)
                    .header("Authorization", authHeader(apiKey, deviceId(context)))
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.code() == 401 || response.code() == 403) {
                    Log.w(TAG, "Test connection: server rejected the key, HTTP " + response.code());
                    return ConnectionResult.failure("Server rejected the API key (HTTP " + response.code() + ") - check it was copied correctly, with no extra spaces");
                }
                if (!response.isSuccessful()) {
                    Log.w(TAG, "Test connection: HTTP " + response.code());
                    return ConnectionResult.failure("Server returned HTTP " + response.code());
                }
                String body = response.body() != null ? response.body().string() : null;
                if (body == null) {
                    Log.w(TAG, "Test connection: empty response body");
                    return ConnectionResult.failure("Server returned an empty response");
                }
                String serverName = new JSONObject(body).optString("ServerName", "");
                Log.d(TAG, "Test connection: reached " + (!serverName.isEmpty() ? serverName : "Jellyfin"));
                return ConnectionResult.success(!serverName.isEmpty() ? serverName : "Jellyfin");
            }
        } catch (UnknownHostException e) {
            Log.w(TAG, "Test connection: unknown host - " + e.getMessage());
            return ConnectionResult.failure("Couldn't find that server - check the URL");
        } catch (ConnectException | SocketTimeoutException e) {
            Log.w(TAG, "Test connection: connect/timeout - " + e.getMessage());
            return ConnectionResult.failure("Couldn't reach that server - check the URL, port, and that it's on the same network");
        } catch (SSLException e) {
            Log.w(TAG, "Test connection: TLS error - " + e.getMessage());
            return ConnectionResult.failure("HTTPS/certificate error - try http:// instead if this is a local server");
        } catch (Exception e) {
            Log.w(TAG, "Jellyfin connection test failed", e);
            return ConnectionResult.failure(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    /**
     * "MediaBrowser Token=..." rather than the query-string ?ApiKey= form -
     * both are accepted by Jellyfin (AuthorizationContext falls back to the
     * query param only when EnableLegacyAuthorization is on), but a header
     * keeps the key out of any logged URL - see openWithFallback()'s
     * Log.d in PlayerLauncher, and this class's own Log.w on a failed
     * request.
     */
    private static String authHeader(String apiKey, String deviceId) {
        return "MediaBrowser Token=\"" + apiKey + "\", Client=\"TVRelay\", Device=\"Android TV\", "
                + "DeviceId=\"" + deviceId + "\", Version=\"" + BuildConfig.VERSION_NAME + "\"";
    }

    /** A per-device-install id shown in Jellyfin's own "connected devices" list - not a secret. */
    private static String deviceId(Context context) {
        String id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        return id != null ? id : "tvrelay-unknown-device";
    }

    /**
     * Trims, strips a trailing slash, and prepends http:// when the user
     * left the scheme off entirely - a LAN Jellyfin server URL like
     * "192.168.1.50:8096" is a very plausible thing to type. Deliberately
     * defaults to http, not https: this is normally a bare LAN IP with no
     * certificate at all - see network_security_config.xml, which is what
     * actually makes http reachable at this target SDK.
     */
    static String normalizeBaseUrl(String rawUrl) {
        String trimmed = rawUrl == null ? "" : rawUrl.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (!trimmed.contains("://")) {
            trimmed = "http://" + trimmed;
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
