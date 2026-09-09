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
 * recommendation card is essentially always current/recent content.
 */
final class ExactMatchPicker<T> {

    /** Displayed candidates are capped here - see ranked(). */
    private static final int MAX_RANKED = 20;

    private final boolean hideAlternateTitles;
    private final List<Entry<T>> offered = new ArrayList<>();

    private T bestExactMatch;
    private boolean bestExactHasAlternateTitle = true; // worst case, so the first exact match always wins
    private int bestExactYear = Integer.MIN_VALUE;

    /**
     * @param hideAlternateTitles when true, a candidate offered with
     *                            hasAlternateTitle=true (see offer()) is
     *                            excluded from consideration entirely -
     *                            never becomes the relevance fallback, never
     *                            becomes the best exact match, never appears
     *                            in ranked() - rather than merely being
     *                            sorted after non-alternate-title matches.
     *                            Backs Settings' "Hide alternate-title (aka)
     *                            matches" toggle (default off) - see
     *                            Preferences.isAlternateTitlesHidden().
     */
    ExactMatchPicker(boolean hideAlternateTitles) {
        this.hideAlternateTitles = hideAlternateTitles;
    }

    /**
     * Call once per search result, in the order the API returned them.
     * hasAlternateTitle is meaningless when isExactTitleMatch is false, same
     * as year - see ranked()'s javadoc for why this exists and what it
     * demotes. A no-op when this candidate has an alternate title and the
     * constructor's hideAlternateTitles was true - see that param's javadoc.
     */
    void offer(T candidate, boolean isExactTitleMatch, boolean hasAlternateTitle, int year) {
        if (hasAlternateTitle && hideAlternateTitles) {
            return;
        }
        offered.add(new Entry<>(candidate, isExactTitleMatch, hasAlternateTitle, year));
        if (isExactTitleMatch && isBetterExactMatch(hasAlternateTitle, year)) {
            bestExactMatch = candidate;
            bestExactHasAlternateTitle = hasAlternateTitle;
            bestExactYear = year;
        }
    }

    /** Mirrors ranked()'s two-key sort (no-alternate-title first, then higher year). */
    private boolean isBetterExactMatch(boolean hasAlternateTitle, int year) {
        if (bestExactMatch == null) {
            return true;
        }
        if (hasAlternateTitle != bestExactHasAlternateTitle) {
            return !hasAlternateTitle;
        }
        return year > bestExactYear;
    }

    boolean hasExactMatch() {
        return bestExactMatch != null;
    }

    /**
     * If any exact title match exists: ONLY the exact matches, sorted with
     * candidates that have a distinct alternate/original title (see
     * TitleCandidate.akaTitle - what the chooser shows as an "aka <name>"
     * line) after ones that don't, newest year first within each of those
     * two tiers - capped at MAX_RANKED. Otherwise: the relevance-fallback
     * candidates in offer order.
     *
     * The alternate-title demotion (2026-09-08) was confirmed necessary
     * against a real case: TMDB returns 11 exact matches for "Begin Again",
     * and the well-known 2014 film was the *oldest* of them, so pure
     * year-descending sorting pushed it off a 6-item cap entirely beneath
     * several obscure same-titled foreign productions. Deliberately keyed
     * on whether a candidate *shows* an aka line, not on which specific
     * field (localized vs. original title) matched the query - the latter
     * wouldn't have fixed this case, since every one of those foreign
     * entries matched via its own localized English title, not its
     * native-script original one. For a search where no candidate has an
     * alternate title (the common case), this key always compares equal
     * across every candidate, so the sort falls through to the plain
     * year-descending order this always used.
     *
     * Confirmed real bug, fixed here: this used to pad the list with
     * relevance-fallback candidates whenever there were fewer than
     * MAX_RANKED exact matches - so searching "Backrooms" (2+ exact
     * matches) also listed "The Backrooms" and "Backwoods" as if they were
     * equally valid picks, when neither is even the same title. The
     * chooser must only ever offer titles that actually match what the
     * user searched for.
     *
     * Exact matches are sorted with a stable comparator (ties keep the
     * earliest-offered, i.e. most relevant, candidate first), the same
     * two-key preference isBetterExactMatch() uses.
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
        Collections.sort(exact, (a, b) -> {
            int alternateCompare = Boolean.compare(a.hasAlternateTitle, b.hasAlternateTitle);
            return alternateCompare != 0 ? alternateCompare : Integer.compare(b.year, a.year);
        });

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
        final boolean hasAlternateTitle;
        final int year;

        Entry(T candidate, boolean isExactTitleMatch, boolean hasAlternateTitle, int year) {
            this.candidate = candidate;
            this.isExactTitleMatch = isExactTitleMatch;
            this.hasAlternateTitle = hasAlternateTitle;
            this.year = year;
        }
    }
}
