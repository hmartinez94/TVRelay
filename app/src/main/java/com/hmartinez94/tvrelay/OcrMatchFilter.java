package com.hmartinez94.tvrelay;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Decides which TMDB/TheTVDB candidates to trust for a title read off the
 * screen by OCR - the layer between "what the recognizer said" and "what we
 * open".
 *
 * OCR output is often one character off ("You Should Have Lef", "The
 * Babadool"), while the search backends are relevance-ranked and will happily
 * return the closest thing for ANY string, including a misread rating ("94%"
 * returned "Toshkent 94" - confirmed 2026-09-21). So a search hit on its own
 * proves nothing about OCR text. Rule:
 *  1. Any exact title match wins - the list is returned as-is, so ranking and
 *     the ambiguous-match chooser behave exactly as for any other title.
 *  2. Otherwise keep only candidates whose title is a NEAR match - normalized
 *     Levenshtein similarity of at least MIN_SIMILARITY (1 - edit distance /
 *     longer length). "You Should Have Lef" vs "You Should Have Left" is 0.95;
 *     "94%" vs "Toshkent 94" is under 0.3.
 *  3. Nothing left means decline: the caller must not guess.
 * Short strings never get rule 2 - one wrong letter is a different word
 * ("Us" / "Up"), not a misread.
 */
final class OcrMatchFilter {

    /** Below this length a near match is too easy to get by accident. */
    static final int MIN_FUZZY_LENGTH = 6;

    /** Fraction of the title that must survive: allows one wrong letter from ~7 characters up. */
    static final double MIN_SIMILARITY = 0.85;

    private OcrMatchFilter() {
    }

    /** Candidates worth offering for this OCR text, best first; empty means decline. */
    static List<TitleCandidate> accept(String ocrTitle, List<TitleCandidate> resolved) {
        for (TitleCandidate candidate : resolved) {
            if (candidate.isExactMatch) {
                return resolved;
            }
        }
        String ocr = normalize(ocrTitle);
        if (ocr.length() < MIN_FUZZY_LENGTH) {
            return new ArrayList<>();
        }
        List<TitleCandidate> near = new ArrayList<>();
        for (TitleCandidate candidate : resolved) {
            if (similarity(ocr, normalize(candidate.displayTitle)) >= MIN_SIMILARITY) {
                near.add(candidate);
            }
        }
        return near;
    }

    /** 1.0 = identical, 0.0 = nothing in common. Both inputs must already be normalize()d. */
    static double similarity(String a, String b) {
        int longer = Math.max(a.length(), b.length());
        if (longer == 0) {
            return 1.0;
        }
        return 1.0 - (double) editDistance(a, b) / longer;
    }

    /** Lower case, punctuation to spaces, whitespace collapsed - so "Mission: Impossible" and "mission impossible" compare equal. */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        boolean lastSpace = true;
        for (char c : text.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
                lastSpace = false;
            } else if (!lastSpace) {
                out.append(' ');
                lastSpace = true;
            }
        }
        return out.toString().trim();
    }

    /** Classic Levenshtein distance (insert/delete/substitute, each cost 1), two-row version. */
    static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
