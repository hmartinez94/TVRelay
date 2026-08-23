package com.hmartinez94.tvrelay;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * The cleaned/raw/after-colon query-variant fallback chain shared by
 * TvdbClient and TmdbClient - previously duplicated near-verbatim in both,
 * which is exactly how two real bugs ended up in both copies at once
 * (caught by code review, 2026-08-23): the raw-title search was
 * unconditionally overwriting a better cleaned-title fallback, and the
 * after-colon variant was computed from the raw title instead of the
 * cleaned one, so a title needing both fixes at once (a franchise-prefix
 * label AND a trailing "(VOSE)"-style parenthetical) never got both
 * applied together. Fixed once, here, instead of in two places that can
 * drift out of sync again.
 */
final class TitleSearchFallbacks {

    private TitleSearchFallbacks() {
    }

    /**
     * Tries, in order: the title with trailing parentheticals stripped
     * (TitleCleanup.stripTrailingParentheticals), the untouched title, and -
     * only if neither matched anything exactly - the cleaned title with a
     * leading "Label: " prefix also stripped (TitleCleanup.afterFirstColon).
     * Returns immediately on the first exact match; otherwise returns the
     * best (first, most-cleaned) non-empty relevance-fallback result seen,
     * or an empty list if nothing came back at all. `search` performs one
     * provider search for a single title string - TvdbClient/TmdbClient
     * each pass their own (auth-retry-wrapped, or API-key-aware) version.
     */
    static List<TitleCandidate> resolve(String title, Function<String, List<TitleCandidate>> search) {
        List<TitleCandidate> best = Collections.emptyList();

        String cleaned = TitleCleanup.stripTrailingParentheticals(title);
        if (!cleaned.isEmpty() && !cleaned.equals(title)) {
            List<TitleCandidate> candidates = search.apply(cleaned);
            if (hasExactMatch(candidates)) {
                return candidates;
            }
            if (!candidates.isEmpty()) {
                best = candidates;
            }
        }

        List<TitleCandidate> raw = search.apply(title);
        if (hasExactMatch(raw)) {
            return raw;
        }
        // Only fills in for an empty `best` - a raw-title guess must never
        // displace an already-found cleaned-title guess, since the cleaned
        // title is the more trustworthy of the two when both are just
        // relevance fallbacks rather than exact matches.
        if (best.isEmpty() && !raw.isEmpty()) {
            best = raw;
        }

        // See TitleCleanup.afterFirstColon. Computed from the cleaned title
        // (not the raw one) so a title needing both fixes gets both at
        // once - only trusted if it actually resolves something exactly,
        // so a title that legitimately contains a colon and simply has no
        // match at all isn't silently replaced by an unrelated guess.
        String afterColon = TitleCleanup.afterFirstColon(cleaned);
        if (afterColon != null) {
            List<TitleCandidate> viaAfterColon = search.apply(afterColon);
            if (hasExactMatch(viaAfterColon)) {
                return viaAfterColon;
            }
        }

        return best;
    }

    private static boolean hasExactMatch(List<TitleCandidate> ranked) {
        return !ranked.isEmpty() && ranked.get(0).isExactMatch;
    }
}
