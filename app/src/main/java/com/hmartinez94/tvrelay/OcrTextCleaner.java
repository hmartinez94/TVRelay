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
 * or that case-insensitively match a caller-supplied denylist of known TV
 * detail-page UI-chrome labels (R.array.ocr_chrome_denylist - "PLAY",
 * "TRAILER", etc. - text that ML Kit will happily also recognize as its own
 * block), then take the block with
 * the TALLEST font (largest line bounding-box height), on the assumption
 * that the title is rendered in the biggest type on the page. Falls back to
 * the first non-empty block if nothing survives the filter, rather than
 * returning nothing at all.
 *
 * CORRECTED 2026-09-07 from "largest bounding-box AREA" to "tallest font":
 * on a real Fire TV detail page ("Ruthless People", confirmed via live
 * logcat) the multi-line synopsis paragraph has more total area than the
 * short two-word title, so the old area rule picked the synopsis and the
 * title never surfaced. Font height is a far better title signal - the
 * title is always the biggest type on these detail pages, regardless of how
 * few words it is. Paired with the tighter title-only crop in
 * OcrCaptureConfig (see there); either fix alone helps, both together are
 * belt-and-suspenders. Still validate against new layouts: a wrong pick
 * fails safe (a resolve miss / NO_TEXT_FOUND), never a wrong title opening.
 */
final class OcrTextCleaner {

    private static final int MIN_TITLE_LENGTH = 2;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private OcrTextCleaner() {
    }

    /**
     * Returns the best-guess title, or null if the OCR result had nothing usable in it.
     *
     * @param chromeDenylist UI-chrome labels to reject outright (see
     *        R.array.ocr_chrome_denylist), resolved by the caller against
     *        the real system locale (see LauncherLocale) - threaded in as a
     *        parameter rather than read here so this class stays
     *        context-free and directly testable. May be null or empty; the
     *        tallest-font heuristic then carries the whole job, same as
     *        before this denylist existed.
     */
    static String extractTitle(Text result, String[] chromeDenylist) {
        if (result == null) {
            return null;
        }

        Text.TextBlock firstNonEmpty = null;
        Text.TextBlock best = null;
        int bestHeight = -1;
        long bestAreaTiebreak = -1;

        for (Text.TextBlock block : result.getTextBlocks()) {
            String text = collapseWhitespace(block.getText());
            if (text.isEmpty()) {
                continue;
            }
            if (firstNonEmpty == null) {
                firstNonEmpty = block;
            }
            if (text.length() < MIN_TITLE_LENGTH || isChrome(text, chromeDenylist)) {
                continue;
            }
            // Tallest font wins - the title is the biggest type on the page.
            // Area is only a tiebreak between two blocks of equal line
            // height (e.g. a title that wrapped onto a second same-size line
            // vs. a stray equal-height fragment).
            int height = maxLineHeight(block);
            long area = boundingBoxArea(block);
            if (height > bestHeight || (height == bestHeight && area > bestAreaTiebreak)) {
                bestHeight = height;
                bestAreaTiebreak = area;
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

    /**
     * Tallest line bounding-box height in the block. The title is rendered
     * in the biggest type on these detail pages, so its line height beats
     * the smaller synopsis/rating/cast text even when those have more total
     * area - see the class doc's 2026-09-07 correction. Uses per-line boxes
     * (not the whole-block box) so a multi-line paragraph isn't scored by
     * its tall overall height; falls back to the block box if line boxes are
     * unavailable.
     */
    private static int maxLineHeight(Text.TextBlock block) {
        int max = 0;
        for (Text.Line line : block.getLines()) {
            Rect bounds = line.getBoundingBox();
            if (bounds != null && bounds.height() > max) {
                max = bounds.height();
            }
        }
        if (max == 0) {
            Rect blockBounds = block.getBoundingBox();
            if (blockBounds != null) {
                max = blockBounds.height();
            }
        }
        return max;
    }

    private static boolean isChrome(String text, String[] chromeDenylist) {
        if (chromeDenylist == null) {
            return false;
        }
        String upper = text.toUpperCase(Locale.ROOT);
        for (String denied : chromeDenylist) {
            // Both sides normalized, so a localized denylist entry does not
            // have to be typed in upper case to work.
            if (denied != null && upper.equals(denied.toUpperCase(Locale.ROOT))) {
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
