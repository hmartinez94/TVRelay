package com.hmartinez94.tvrelay;

import java.util.regex.Pattern;

/** Shared by TvdbClient and TmdbClient - both need the same launcher-quirk cleanup before searching. */
final class TitleCleanup {

    // Trailing parentheticals the launcher appends to indicate dub/sub
    // version - "(VO)", "(VE)", "(VOSE)", etc. Not part of the real title
    // and they break the search if left in. Strip ALL of them, not just
    // one: some titles carry more than one, e.g. "The Crow (El Cuervo) (VOSE)".
    private static final Pattern TRAILING_PARENTHETICAL = Pattern.compile("\\s*\\([^)]*\\)\\s*$");

    private TitleCleanup() {
    }

    static String stripTrailingParentheticals(String title) {
        String cleaned = title;
        while (true) {
            String next = TRAILING_PARENTHETICAL.matcher(cleaned).replaceAll("").trim();
            if (next.equals(cleaned)) {
                return cleaned;
            }
            cleaned = next;
        }
    }

    /**
     * Everything after a leading "Label: " prefix, or null if there isn't
     * one. Confirmed real case: a launcher card read "Star Wars: The
     * Mandalorian and Grogu", but neither TheTVDB nor TMDB has that string
     * as an exact title anywhere - the real movie's title there is just
     * "The Mandalorian and Grogu". Only the FIRST colon is stripped, not the
     * last, so a title with more than one (e.g. a hypothetical "Star Wars:
     * Andor: Season One") keeps everything after the outermost label rather
     * than being cut down to just the last segment.
     *
     * Callers must only use this as a fallback when the untouched title
     * already failed to match anything exactly, and only trust the result
     * if IT matches exactly - never unconditionally, since plenty of real
     * titles legitimately contain a colon themselves (e.g. "Mission:
     * Impossible") and must keep matching as their full, unmodified string.
     */
    static String afterFirstColon(String title) {
        int index = title.indexOf(": ");
        return index >= 0 && index + 2 < title.length() ? title.substring(index + 2).trim() : null;
    }
}
