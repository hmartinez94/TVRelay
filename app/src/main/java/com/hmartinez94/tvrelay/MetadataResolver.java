package com.hmartinez94.tvrelay;

import android.content.Context;

import java.util.List;

/** Resolves a title to candidates (or a single launchable match) via whichever provider is chosen in Settings. */
final class MetadataResolver {

    private MetadataResolver() {
    }

    /**
     * All plausible matches for a title, most-likely first - see
     * ExactMatchPicker.ranked(). Empty (never null) if nothing usable came
     * back. Blocking network call(s) - call only from a background thread.
     */
    static List<TitleCandidate> resolveCandidates(Context context, String title) {
        if (Preferences.getMetadataProvider(context) == MetadataProvider.TMDB) {
            return TmdbClient.findCandidates(context, title);
        }
        return TvdbClient.findCandidates(title);
    }

    /**
     * True when a candidate list is genuinely ambiguous: two or more
     * candidates whose title matched the search exactly (see
     * TitleCandidate.isExactMatch). A single exact match, or none at all
     * (top-relevance fallback), isn't ambiguous - candidates.get(0) is used
     * directly in that case, exactly as before this existed.
     */
    static boolean isAmbiguous(List<TitleCandidate> candidates) {
        int exactCount = 0;
        for (TitleCandidate candidate : candidates) {
            if (candidate.isExactMatch && ++exactCount >= 2) {
                return true;
            }
        }
        return false;
    }

    /**
     * Turns a candidate into a launchable match. A no-op for TheTVDB
     * candidates (already resolved); fetches the IMDB id for TMDB
     * candidates, which is deliberately not done for every candidate
     * upfront - see TmdbClient. Returns null if resolution fails. Blocking
     * network call for TMDB - call only from a background thread.
     */
    static TvdbMatch resolve(Context context, TitleCandidate candidate) {
        if (candidate.isResolved()) {
            return new TvdbMatch(candidate.imdbId, candidate.type);
        }
        String imdbId = TmdbClient.resolveImdbId(context, candidate);
        return imdbId != null ? new TvdbMatch(imdbId, candidate.type) : null;
    }
}
