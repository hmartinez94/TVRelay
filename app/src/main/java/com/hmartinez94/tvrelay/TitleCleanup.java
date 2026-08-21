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
}
