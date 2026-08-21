package com.hmartinez94.tvrelay;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Minimal TheTVDB v4 client: resolves a title to an IMDB id, which is what
 * the Nuvio/Stremio/Kodi deep links need.
 *
 * The API key comes from BuildConfig.TVDB_API_KEY, generated from
 * TVDB_API_KEY in local.properties (not versioned). Get a free key at
 * https://www.thetvdb.com/dashboard/account/apikeys - free under $50k/year
 * revenue, attribution required (see the "About" screen and the README).
 *
 * Unlike TMDB, a TheTVDB search result already carries external ids
 * (remote_ids), so resolving a title is a single network call instead of
 * two (TMDB needs a search call, then a separate external_ids call).
 */
final class TvdbClient {

    private static final String TAG = "TvdbClient";
    private static final String BASE_URL = "https://api4.thetvdb.com/v4";
    private static final okhttp3.MediaType JSON = okhttp3.MediaType.get("application/json; charset=utf-8");
    private static final Pattern IMDB_ID = Pattern.compile("tt\\d+");

    // Tokens are valid one month per TheTVDB's API. Re-login a little early
    // so a long-lived accessibility service process never gets caught
    // mid-call with an about-to-expire token.
    private static final long TOKEN_LIFETIME_MS = 25L * 24 * 60 * 60 * 1000;

    private static final OkHttpClient httpClient = new OkHttpClient();

    private static volatile String token;
    private static volatile long tokenIssuedAt;

    private TvdbClient() {
    }

    /**
     * Blocking network call(s) - must only ever run on a background
     * thread, never the accessibility service's main thread.
     */
    static TvdbMatch findImdbId(String title) {
        String cleaned = TitleCleanup.stripTrailingParentheticals(title);
        if (!cleaned.isEmpty() && !cleaned.equals(title)) {
            TvdbMatch match = resolveTitle(cleaned);
            if (match != null) {
                return match;
            }
        }
        return resolveTitle(title);
    }

    private static TvdbMatch resolveTitle(String title) {
        try {
            return search(title, true);
        } catch (AuthException e) {
            // Cached token was rejected - force a fresh login once and retry.
            token = null;
            try {
                return search(title, false);
            } catch (Exception retryFailure) {
                Log.e(TAG, "Search retry after re-login failed", retryFailure);
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Search failed for: " + title, e);
            return null;
        }
    }

    private static TvdbMatch search(String title, boolean allowAuthRetry)
            throws IOException, JSONException, AuthException {
        String bearer = getToken();
        String url = BASE_URL + "/search?query=" + URLEncoder.encode(title, "UTF-8") + "&limit=5";
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + bearer)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.code() == 401 && allowAuthRetry) {
                throw new AuthException();
            }
            if (!response.isSuccessful()) {
                Log.w(TAG, "TheTVDB search failed: " + response.code());
                return null;
            }
            String body = response.body() != null ? response.body().string() : null;
            if (body == null) {
                return null;
            }
            JSONObject json = new JSONObject(body);
            JSONArray results = json.optJSONArray("data");
            if (results == null) {
                return null;
            }

            // TheTVDB's search is relevance/popularity-ranked, not
            // exact-match: querying "Backrooms" can return a distinct,
            // more popular title like "The Backrooms" ahead of the actual
            // "Backrooms" entry. Scan every candidate for one whose own
            // name/title exactly matches the query first, and only fall
            // back to the top relevance result if nothing matches exactly -
            // confirmed as a real mismatch on-device (clicking "Backrooms"
            // opened "The Backrooms" in Nuvio).
            String normalizedQuery = normalize(title);
            TvdbMatch topRelevanceResult = null;

            for (int i = 0; i < results.length(); i++) {
                JSONObject result = results.getJSONObject(i);
                String type = result.optString("type", "");
                // Temporary diagnostic logging while confirming the
                // exact-match fix actually sees the right candidates -
                // remove once confirmed working across real queries.
                Log.d(TAG, "Candidate " + i + ": type=" + type
                        + " name=[" + result.optString("name", "") + "]"
                        + " title=[" + result.optString("title", "") + "]"
                        + " remote_ids=" + result.optJSONArray("remote_ids"));
                MediaType mediaType;
                if ("movie".equals(type)) {
                    mediaType = MediaType.MOVIE;
                } else if ("series".equals(type)) {
                    mediaType = MediaType.SERIES;
                } else {
                    continue;
                }
                String imdbId = extractImdbId(result);
                if (imdbId == null) {
                    continue;
                }
                TvdbMatch candidate = new TvdbMatch(imdbId, mediaType);
                if (topRelevanceResult == null) {
                    topRelevanceResult = candidate;
                }
                if (normalizedQuery.equals(normalize(result.optString("name", "")))
                        || normalizedQuery.equals(normalize(result.optString("title", "")))) {
                    return candidate;
                }
            }

            if (topRelevanceResult != null) {
                Log.d(TAG, "No exact title match for \"" + title + "\" - using top relevance result");
                return topRelevanceResult;
            }
            Log.d(TAG, "No usable TheTVDB result for: " + title);
            return null;
        }
    }

    private static String normalize(String s) {
        // Deliberately no article-stripping ("The Backrooms" -> "backrooms")
        // or other fuzzing here: that would make the exact-match check
        // above match "Backrooms" and "The Backrooms" equally again,
        // undoing the fix this exists for.
        return s.trim().toLowerCase(Locale.ROOT);
    }

    private static String extractImdbId(JSONObject result) {
        JSONArray remoteIds = result.optJSONArray("remote_ids");
        if (remoteIds == null) {
            return null;
        }
        for (int i = 0; i < remoteIds.length(); i++) {
            JSONObject remoteId = remoteIds.optJSONObject(i);
            if (remoteId == null) {
                continue;
            }
            // Matched by id shape rather than by "sourceName": TheTVDB's
            // OpenAPI spec types sourceName as a free-form string with no
            // documented enum of values, but "tt" + digits is uniquely
            // IMDB's id format among the remote ids TheTVDB tracks (its
            // own ids are plain numbers, EIDR/Zap2it use different shapes
            // entirely). Not verified against a live response yet - confirm
            // on the first real search call (see plan verification step 2)
            // and switch to a sourceName check if this ever proves unreliable.
            String id = remoteId.optString("id", "");
            if (IMDB_ID.matcher(id).matches()) {
                return id;
            }
        }
        return null;
    }

    private static synchronized String getToken() throws IOException, JSONException {
        String cached = token;
        if (cached != null && System.currentTimeMillis() - tokenIssuedAt < TOKEN_LIFETIME_MS) {
            return cached;
        }
        return login();
    }

    private static String login() throws IOException, JSONException {
        String apiKey = BuildConfig.TVDB_API_KEY;
        if (apiKey.isEmpty()) {
            Log.w(TAG, "TVDB_API_KEY is empty - add it to local.properties");
        }
        JSONObject payload = new JSONObject();
        payload.put("apikey", apiKey);

        Request request = new Request.Builder()
                .url(BASE_URL + "/login")
                .post(RequestBody.create(payload.toString(), JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("TheTVDB login failed: " + response.code());
            }
            String body = response.body() != null ? response.body().string() : null;
            if (body == null) {
                throw new IOException("TheTVDB login returned an empty body");
            }
            JSONObject json = new JSONObject(body);
            String newToken = json.getJSONObject("data").getString("token");
            token = newToken;
            tokenIssuedAt = System.currentTimeMillis();
            return newToken;
        }
    }

    /** Internal signal that the cached token was rejected; triggers one re-login. */
    private static final class AuthException extends Exception {
    }
}
