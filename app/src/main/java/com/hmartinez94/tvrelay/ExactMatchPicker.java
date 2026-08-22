package com.hmartinez94.tvrelay;

import java.util.Locale;

/**
 * Shared "which search result wins" logic for TvdbClient and TmdbClient.
 * Both providers' searches are relevance-ranked, not exact-match (querying
 * "Backrooms" can return a more popular "The Backrooms" first), and even a
 * single exact title match isn't unique - "Obsession" matches six distinct
 * movies/shows across release years on TheTVDB alone. Feed every candidate
 * through offer() as you iterate your own provider's JSON in whatever order
 * it came back in; the first candidate is kept as a relevance fallback, and
 * among exact title matches the highest release year wins, since a
 * recommendation card is essentially always current/recent content. See
 * CLAUDE.md for the on-device cases this was built from.
 */
final class ExactMatchPicker<T> {

    private T topRelevanceResult;
    private T bestExactMatch;
    private int bestExactYear = Integer.MIN_VALUE;

    /** Call once per search result, in the order the API returned them. */
    void offer(T candidate, boolean isExactTitleMatch, int year) {
        if (topRelevanceResult == null) {
            topRelevanceResult = candidate;
        }
        if (isExactTitleMatch && (bestExactMatch == null || year > bestExactYear)) {
            bestExactMatch = candidate;
            bestExactYear = year;
        }
    }

    boolean hasExactMatch() {
        return bestExactMatch != null;
    }

    /** The best exact-title match by year, else the top relevance result, else null if offer() was never called. */
    T result() {
        return bestExactMatch != null ? bestExactMatch : topRelevanceResult;
    }

    /**
     * Deliberately no article-stripping ("The Backrooms" -> "backrooms")
     * or other fuzzing: that would make "Backrooms" and "The Backrooms"
     * match equally again, undoing the exact-match fix this exists for.
     */
    static String normalize(String s) {
        return s.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses a leading 4-digit year from either a plain "YYYY" (TheTVDB's
     * "year" field) or a "YYYY-MM-DD" date (TMDB's release_date/
     * first_air_date) - both shapes share the same first 4 characters.
     * Unparseable or missing sorts last so it never wins a tiebreak.
     */
    static int parseYear(String s) {
        if (s == null) {
            return Integer.MIN_VALUE;
        }
        String trimmed = s.trim();
        if (trimmed.length() < 4) {
            return Integer.MIN_VALUE;
        }
        try {
            return Integer.parseInt(trimmed.substring(0, 4));
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }
}
