package com.hmartinez94.tvrelay;

import android.graphics.Rect;

import com.google.mlkit.vision.text.Text;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Picks the most likely title out of an ML Kit text-recognition result.
 * Same shape/spirit as TitleCleanup - a small, static, well-commented
 * string heuristic, not a real layout-understanding model.
 *
 * Heuristic: drop text blocks that are too short to plausibly be a title,
 * or that case-insensitively match a small denylist of known TV
 * detail-page UI-chrome labels ("PLAY", "TRAILER", etc. - text that ML Kit
 * will happily also recognize as its own block), then take the largest
 * surviving block by bounding-box area, on the assumption that a rendered
 * title is usually the biggest text on the screen. Falls back to the
 * first non-empty block if nothing survives the filter, rather than
 * returning nothing at all.
 *
 * UNVERIFIED, same epistemic status as YOUTUBE_MARKERS in
 * TvRelayAccessibilityService (see its comment for the reasoning this
 * mirrors): this denylist and the "biggest surviving block wins"
 * heuristic are both blind guesses pending real on-device calibration
 * against an actual captured detail-page frame - nothing here has been
 * tested against real OCR output yet. If a real capture shows this
 * picking the wrong block, recalibrate against that evidence rather than
 * guessing again.
 */
final class OcrTextCleaner {

    private static final int MIN_TITLE_LENGTH = 2;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    // Known UI-chrome labels that can end up as their own ML Kit text
    // block on a detail page and must not be mistaken for the title
    // itself. Guessed, not confirmed against a real capture - see class
    // doc.
    private static final String[] CHROME_DENYLIST = {
            "PLAY", "DETAILS", "ADD TO WATCHLIST", "TRAILER", "MORE INFO", "WATCH NOW"
    };

    private OcrTextCleaner() {
    }

    /** Returns the best-guess title, or null if the OCR result had nothing usable in it. */
    static String extractTitle(Text result) {
        if (result == null) {
            return null;
        }

        Text.TextBlock firstNonEmpty = null;
        Text.TextBlock best = null;
        long bestArea = -1;

        for (Text.TextBlock block : result.getTextBlocks()) {
            String text = collapseWhitespace(block.getText());
            if (text.isEmpty()) {
                continue;
            }
            if (firstNonEmpty == null) {
                firstNonEmpty = block;
            }
            if (text.length() < MIN_TITLE_LENGTH || isChrome(text)) {
                continue;
            }
            long area = boundingBoxArea(block);
            if (area > bestArea) {
                bestArea = area;
                best = block;
            }
        }

        Text.TextBlock chosen = best != null ? best : firstNonEmpty;
        if (chosen == null) {
            return null;
        }
        String cleaned = collapseWhitespace(chosen.getText());
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static long boundingBoxArea(Text.TextBlock block) {
        Rect bounds = block.getBoundingBox();
        return bounds == null ? 0 : (long) bounds.width() * (long) bounds.height();
    }

    private static boolean isChrome(String text) {
        String upper = text.toUpperCase(Locale.ROOT);
        for (String denied : CHROME_DENYLIST) {
            if (upper.equals(denied)) {
                return true;
            }
        }
        return false;
    }

    private static String collapseWhitespace(String text) {
        if (text == null) {
            return "";
        }
        return WHITESPACE.matcher(text).replaceAll(" ").trim();
    }
}
