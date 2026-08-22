package com.hmartinez94.tvrelay;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.Locale;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * TMDB client for users who opt into it in Settings instead of the default
 * TheTVDB. Uses the user's OWN personal TMDB API key (Preferences,
 * user-entered) - never a key bundled with the app. TMDB's terms treat an
 * app that profits from its use as commercial, requiring a paid license;
 * TVRelay itself charges nothing and unlocks nothing behind payment, so
 * each user's own key is used for their own personal, non-commercial TMDB
 * usage - same as using any other TMDB-powered tool with their account.
 * Still subject to TMDB's terms of use (see the in-app help text).
 *
 * Needs two calls per lookup (search, then external_ids) where TheTVDB
 * needs one - the trade a user accepts by opting into TMDB over the default.
 */
final class TmdbClient {

    private static final String TAG = "TmdbClient";
    private static final OkHttpClient httpClient = new OkHttpClient();

    private TmdbClient() {
    }

    /** Blocking network call(s) - call only from a background thread. */
    static TvdbMatch findImdbId(Context context, String title) {
        String apiKey = Preferences.getTmdbApiKey(context).trim();
        if (apiKey.isEmpty()) {
            Log.w(TAG, "No TMDB API key set - add one in Settings");
            return null;
        }
        String cleaned = TitleCleanup.stripTrailingParentheticals(title);
        if (!cleaned.isEmpty() && !cleaned.equals(title)) {
            TvdbMatch match = resolveTitle(apiKey, cleaned);
            if (match != null) {
                return match;
            }
        }
        return resolveTitle(apiKey, title);
    }

    private static TvdbMatch resolveTitle(String apiKey, String title) {
        try {
            Candidate candidate = search(apiKey, title);
            if (candidate == null) {
                return null;
            }
            String imdbId = fetchImdbId(apiKey, candidate.tmdbId, candidate.mediaPath);
            if (imdbId == null) {
                return null;
            }
            MediaType type = "tv".equals(candidate.mediaPath) ? MediaType.SERIES : MediaType.MOVIE;
            return new TvdbMatch(imdbId, type);
        } catch (Exception e) {
            Log.e(TAG, "TMDB lookup failed for: " + title, e);
            return null;
        }
    }

    private static final class Candidate {
        final int tmdbId;
        final String mediaPath; // "movie" or "tv"

        Candidate(int tmdbId, String mediaPath) {
            this.tmdbId = tmdbId;
            this.mediaPath = mediaPath;
        }
    }

    private static Candidate search(String apiKey, String title) throws IOException, JSONException {
        String language = Locale.getDefault().getLanguage();
        String url = "https://api.themoviedb.org/3/search/multi?query=" + URLEncoder.encode(title, "UTF-8")
                + "&api_key=" + apiKey + "&language=" + language;
        Request request = new Request.Builder().url(url).build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                Log.w(TAG, "TMDB search failed: " + response.code());
                return null;
            }
            String body = response.body() != null ? response.body().string() : null;
            if (body == null) {
                return null;
            }
            JSONObject json = new JSONObject(body);
            JSONArray results = json.optJSONArray("results");
            if (results == null) {
                return null;
            }

            // See ExactMatchPicker for why this exists: TMDB's search is
            // relevance-ranked and title collisions are common (same
            // "Obsession" case confirmed against TheTVDB - see CLAUDE.md).
            String normalizedQuery = ExactMatchPicker.normalize(title);
            ExactMatchPicker<Candidate> picker = new ExactMatchPicker<>();

            for (int i = 0; i < results.length(); i++) {
                JSONObject result = results.getJSONObject(i);
                String mediaType = result.optString("media_type", "");
                if (!"movie".equals(mediaType) && !"tv".equals(mediaType)) {
                    continue;
                }
                Candidate candidate = new Candidate(result.getInt("id"), mediaType);
                boolean isMovie = "movie".equals(mediaType);
                String name = result.optString(isMovie ? "title" : "name", "");
                String originalName = result.optString(isMovie ? "original_title" : "original_name", "");
                boolean isExactMatch = normalizedQuery.equals(ExactMatchPicker.normalize(name))
                        || normalizedQuery.equals(ExactMatchPicker.normalize(originalName));
                String dateField = isMovie ? "release_date" : "first_air_date";
                int year = ExactMatchPicker.parseYear(result.optString(dateField, ""));
                picker.offer(candidate, isExactMatch, year);
            }
            Candidate resolved = picker.result();
            if (resolved != null && !picker.hasExactMatch()) {
                Log.d(TAG, "No exact title match for \"" + title + "\" - using top relevance result");
            }
            return resolved;
        }
    }

    private static String fetchImdbId(String apiKey, int tmdbId, String mediaPath) throws IOException, JSONException {
        String url = "https://api.themoviedb.org/3/" + mediaPath + "/" + tmdbId + "/external_ids?api_key=" + apiKey;
        Request request = new Request.Builder().url(url).build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                Log.w(TAG, "TMDB external_ids failed: " + response.code());
                return null;
            }
            String body = response.body() != null ? response.body().string() : null;
            if (body == null) {
                return null;
            }
            JSONObject json = new JSONObject(body);
            String imdbId = json.optString("imdb_id", "");
            return (imdbId.isEmpty() || "null".equals(imdbId)) ? null : imdbId;
        }
    }

    /**
     * Reachability/key-validity check for the Settings "Test key" action.
     * Reuses the same /search/multi endpoint the real lookup uses (rather
     * than a separate, unverified endpoint) - a 200 confirms the key
     * works regardless of whether the query matches anything; TMDB
     * returns 401 for an invalid key.
     */
    static boolean testApiKey(String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return false;
        }
        try {
            String url = "https://api.themoviedb.org/3/search/multi?query=test&api_key=" + apiKey.trim();
            Request request = new Request.Builder().url(url).build();
            try (Response response = httpClient.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            Log.w(TAG, "TMDB key test failed", e);
            return false;
        }
    }
}
