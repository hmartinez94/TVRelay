package com.hmartinez94.tvrelay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    /** Displayed candidates are capped here - see ranked(). */
    private static final int MAX_RANKED = 6;

    private final List<Entry<T>> offered = new ArrayList<>();

    private T topRelevanceResult;
    private T bestExactMatch;
    private int bestExactYear = Integer.MIN_VALUE;

    /** Call once per search result, in the order the API returned them. */
    void offer(T candidate, boolean isExactTitleMatch, int year) {
        offered.add(new Entry<>(candidate, isExactTitleMatch, year));
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
     * If any exact title match exists: ONLY the exact matches, newest year
     * first, capped at MAX_RANKED. Otherwise: the relevance-fallback
     * candidates in offer order, so ranked().get(0) still matches result()'s
     * fallback behavior when there's no exact match at all.
     *
     * Confirmed real bug, fixed here: this used to pad the list with
     * relevance-fallback candidates whenever there were fewer than
     * MAX_RANKED exact matches - so searching "Backrooms" (2+ exact
     * matches) also listed "The Backrooms" and "Backwoods" as if they were
     * equally valid picks, when neither is even the same title. The
     * chooser must only ever offer titles that actually match what the
     * user searched for.
     *
     * ranked().get(0) is always identical to result() - exact matches are
     * sorted with a stable comparator (ties keep the earliest-offered, i.e.
     * most relevant, candidate first), which is the same tiebreak result()
     * itself uses via offer()'s strict ">" check.
     */
    List<T> ranked() {
        List<Entry<T>> exact = new ArrayList<>();
        List<Entry<T>> rest = new ArrayList<>();
        for (Entry<T> entry : offered) {
            (entry.isExactTitleMatch ? exact : rest).add(entry);
        }
        // Integer.compare, not subtraction: a candidate with an unknown
        // year (Integer.MIN_VALUE) would otherwise overflow a plain
        // "b.year - a.year" comparison and sort first instead of last.
        Collections.sort(exact, (a, b) -> Integer.compare(b.year, a.year));

        List<Entry<T>> source = exact.isEmpty() ? rest : exact;
        List<T> ranked = new ArrayList<>(Math.min(source.size(), MAX_RANKED));
        for (Entry<T> entry : source) {
            if (ranked.size() >= MAX_RANKED) {
                break;
            }
            ranked.add(entry.candidate);
        }
        return ranked;
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

    private static final class Entry<T> {
        final T candidate;
        final boolean isExactTitleMatch;
        final int year;

        Entry(T candidate, boolean isExactTitleMatch, int year) {
            this.candidate = candidate;
            this.isExactTitleMatch = isExactTitleMatch;
            this.year = year;
        }
    }
}
