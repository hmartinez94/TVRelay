package com.hmartinez94.tvrelay;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * TMDB client - the default metadata provider (see Preferences). Uses
 * Preferences.getEffectiveTmdbApiKey(): the app's own bundled key
 * (BuildConfig.TMDB_API_KEY) unless the user has entered their own in
 * Settings, in which case theirs takes priority - e.g. if the shared
 * default is ever rate-limited. See CLAUDE.md for the reasoning behind
 * bundling a default key (2026-08-23 decision).
 *
 * Unlike TheTVDB, a TMDB search result doesn't carry an external id -
 * that's a separate call (fetchImdbId/resolveImdbId, /{type}/{id}/external_ids)
 * made only for the single candidate the user ends up with, not for every
 * candidate a search returns. That keeps an unambiguous lookup at the same
 * two calls it always needed (search, then external_ids for the winner);
 * only picking from an ambiguous list costs one more, when it happens at all.
 */
final class TmdbClient {

    private static final String TAG = "TmdbClient";
    private static final OkHttpClient httpClient = new OkHttpClient();

    private TmdbClient() {
    }

    /**
     * Every plausible match for a title, most-likely first - see
     * ExactMatchPicker.ranked(). Empty (never null) if nothing usable came
     * back, including when no API key is set. None of the returned
     * candidates carry an IMDB id yet - see resolveImdbId(). Blocking
     * network call(s) - call only from a background thread.
     */
    static List<TitleCandidate> findCandidates(Context context, String title) {
        String apiKey = Preferences.getEffectiveTmdbApiKey(context);
        if (apiKey.isEmpty()) {
            Log.w(TAG, "No TMDB API key available (none bundled, none set in Settings)");
            return Collections.emptyList();
        }
        return TitleSearchFallbacks.resolve(title, t -> resolveCandidates(apiKey, t));
    }

    private static List<TitleCandidate> resolveCandidates(String apiKey, String title) {
        try {
            return search(apiKey, title);
        } catch (Exception e) {
            Log.e(TAG, "TMDB lookup failed for: " + title, e);
            return Collections.emptyList();
        }
    }

    private static List<TitleCandidate> search(String apiKey, String title) throws IOException, JSONException {
        String language = Locale.getDefault().getLanguage();
        String url = "https://api.themoviedb.org/3/search/multi?query=" + URLEncoder.encode(title, "UTF-8")
                + "&api_key=" + apiKey + "&language=" + language;
        Request request = new Request.Builder().url(url).build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                Log.w(TAG, "TMDB search failed: " + response.code());
                return Collections.emptyList();
            }
            String body = response.body() != null ? response.body().string() : null;
            if (body == null) {
                return Collections.emptyList();
            }
            JSONObject json = new JSONObject(body);
            JSONArray results = json.optJSONArray("results");
            if (results == null) {
                return Collections.emptyList();
            }

            // See ExactMatchPicker for why this exists: TMDB's search is
            // relevance-ranked and title collisions are common (same
            // "Obsession" case confirmed against TheTVDB - see CLAUDE.md).
            String normalizedQuery = ExactMatchPicker.normalize(title);
            ExactMatchPicker<TitleCandidate> picker = new ExactMatchPicker<>();

            for (int i = 0; i < results.length(); i++) {
                JSONObject result = results.getJSONObject(i);
                String mediaPath = result.optString("media_type", "");
                if (!"movie".equals(mediaPath) && !"tv".equals(mediaPath)) {
                    continue;
                }
                boolean isMovie = "movie".equals(mediaPath);
                MediaType mediaType = isMovie ? MediaType.MOVIE : MediaType.SERIES;
                String name = result.optString(isMovie ? "title" : "name", "");
                String originalName = result.optString(isMovie ? "original_title" : "original_name", "");
                if (name.isEmpty() && originalName.isEmpty()) {
                    continue;
                }
                boolean isExactMatch = normalizedQuery.equals(ExactMatchPicker.normalize(name))
                        || normalizedQuery.equals(ExactMatchPicker.normalize(originalName));
                String dateField = isMovie ? "release_date" : "first_air_date";
                int year = ExactMatchPicker.parseYear(result.optString(dateField, ""));
                // Kept (not temporary): confirmed real case where this
                // mattered - TMDB's own localized "title" for an obscure
                // French film ("Rendez-Vous", 2015) is literally "Obsession"
                // in English, so it legitimately passes the exact-match
                // check above. That's a TMDB catalog-data quirk, the same
                // class of issue as the "Backrooms" data-coverage gap
                // (see CLAUDE.md) - not something string matching can or
                // should second-guess. This log is what surfaced it.
                Log.d(TAG, "Candidate " + i + ": media_type=" + mediaPath
                        + " name=[" + name + "] original=[" + originalName + "]"
                        + " year=[" + year + "] isExactMatch=" + isExactMatch);
                String displayTitle = !name.isEmpty() ? name : originalName;
                // Shown as an "aka" hint in the chooser so a case like the
                // one above is at least visible to the user instead of
                // indistinguishable from a real "Obsession" - see
                // TitleCandidate/MatchTrayView.
                String akaTitle = !originalName.isEmpty()
                        && !ExactMatchPicker.normalize(originalName).equals(ExactMatchPicker.normalize(displayTitle))
                        ? originalName : null;
                TitleCandidate candidate = TitleCandidate.fromTmdb(
                        displayTitle, akaTitle, year, mediaType, isExactMatch, result.getInt("id"), mediaPath);
                picker.offer(candidate, isExactMatch, year);
            }

            List<TitleCandidate> ranked = picker.ranked();
            if (!ranked.isEmpty() && !picker.hasExactMatch()) {
                Log.d(TAG, "No exact title match for \"" + title + "\" - using top relevance result");
            }
            return ranked;
        }
    }

    /**
     * Fetches the IMDB id for a single TMDB candidate - the second of the
     * two calls a TMDB lookup always needed, done only for the candidate
     * the caller has settled on (see findCandidates). Returns null on
     * failure or if the candidate wasn't a TMDB one to begin with. Blocking
     * network call - call only from a background thread.
     */
    static String resolveImdbId(Context context, TitleCandidate candidate) {
        if (candidate.tmdbMediaPath == null) {
            return null;
        }
        String apiKey = Preferences.getEffectiveTmdbApiKey(context);
        if (apiKey.isEmpty()) {
            return null;
        }
        try {
            return fetchImdbId(apiKey, candidate.tmdbId, candidate.tmdbMediaPath);
        } catch (Exception e) {
            Log.e(TAG, "TMDB external_ids failed for tmdbId=" + candidate.tmdbId, e);
            return null;
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
