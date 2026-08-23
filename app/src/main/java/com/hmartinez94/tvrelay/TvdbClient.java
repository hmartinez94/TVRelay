package com.hmartinez94.tvrelay;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Minimal TheTVDB v4 client: resolves a title to an IMDB id, which is what
 * the Nuvio/Stremio deep links need.
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
     * Every plausible match for a title, most-likely first - see
     * ExactMatchPicker.ranked(). Empty (never null) if nothing usable came
     * back. Blocking network call(s) - must only ever run on a background
     * thread, never the accessibility service's main thread.
     */
    static List<TitleCandidate> findCandidates(String title) {
        return TitleSearchFallbacks.resolve(title, TvdbClient::resolveCandidates);
    }

    private static List<TitleCandidate> resolveCandidates(String title) {
        try {
            return search(title, true);
        } catch (AuthException e) {
            // Cached token was rejected - force a fresh login once and retry.
            token = null;
            try {
                return search(title, false);
            } catch (Exception retryFailure) {
                Log.e(TAG, "Search retry after re-login failed", retryFailure);
                return Collections.emptyList();
            }
        } catch (Exception e) {
            Log.e(TAG, "Search failed for: " + title, e);
            return Collections.emptyList();
        }
    }

    private static List<TitleCandidate> search(String title, boolean allowAuthRetry)
            throws IOException, JSONException, AuthException {
        String bearer = getToken();
        // limit=5 (the old value) was the actual root cause of a confirmed
        // on-device mismatch: searching "Obsession" returns 5 older movies
        // (2019, 1976, 1965, 1949, 1954) before the real, currently-airing
        // 2026 one, which sits at result index 5 - one past the old cutoff.
        // The exact-match-by-year logic below never even saw it. 50 is
        // TheTVDB's default page size and comfortably covers title
        // collisions like this one.
        String url = BASE_URL + "/search?query=" + URLEncoder.encode(title, "UTF-8") + "&limit=50";
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
                return Collections.emptyList();
            }
            String body = response.body() != null ? response.body().string() : null;
            if (body == null) {
                return Collections.emptyList();
            }
            JSONObject json = new JSONObject(body);
            JSONArray results = json.optJSONArray("data");
            if (results == null) {
                return Collections.emptyList();
            }

            // See ExactMatchPicker for why this exists: TheTVDB's search
            // is relevance-ranked and title collisions are common
            // ("Backrooms" -> "The Backrooms"; "Obsession" matched six
            // distinct titles across release years) - both confirmed
            // on-device (see CLAUDE.md).
            String normalizedQuery = ExactMatchPicker.normalize(title);
            ExactMatchPicker<TitleCandidate> picker = new ExactMatchPicker<>();

            for (int i = 0; i < results.length(); i++) {
                JSONObject result = results.getJSONObject(i);
                String type = result.optString("type", "");
                String name = result.optString("name", "");
                String altTitle = result.optString("title", "");
                String yearRaw = result.optString("year", "");
                // Temporary diagnostic logging while confirming the
                // exact-match fix actually sees the right candidates, and
                // checking whether network/image_url are populated on a
                // real search (neither is displayed yet - see
                // TitleCandidate/CLAUDE.md) - remove once confirmed working
                // across real queries.
                Log.d(TAG, "Candidate " + i + ": type=" + type
                        + " name=[" + name + "]"
                        + " title=[" + altTitle + "]"
                        + " year=[" + yearRaw + "]"
                        + " network=[" + result.optString("network", "") + "]"
                        + " image_url=[" + result.optString("image_url", "") + "]"
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
                String displayTitle = !name.isEmpty() ? name : altTitle;
                boolean isExactMatch = normalizedQuery.equals(ExactMatchPicker.normalize(name))
                        || normalizedQuery.equals(ExactMatchPicker.normalize(altTitle));
                int year = ExactMatchPicker.parseYear(yearRaw);
                TitleCandidate candidate = TitleCandidate.fromTvdb(displayTitle, year, mediaType, isExactMatch, imdbId);
                picker.offer(candidate, isExactMatch, year);
            }

            List<TitleCandidate> ranked = picker.ranked();
            if (ranked.isEmpty()) {
                Log.d(TAG, "No usable TheTVDB result for: " + title);
            } else if (!picker.hasExactMatch()) {
                Log.d(TAG, "No exact title match for \"" + title + "\" - using top relevance result");
            }
            return ranked;
        }
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
