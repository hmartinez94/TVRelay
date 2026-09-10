package com.hmartinez94.tvrelay;

import android.content.Context;

/**
 * Tunable constants for the OCR capture fallback pipeline (see
 * OcrCaptureManager / OcrCaptureForegroundService / OcrTextCleaner).
 *
 * The crop band is PER-PLATFORM (see Crop/cropFor() below), not one shared
 * guess - Google TV's EntityActivity and Fire TV's
 * DetailsPageDeepLinkActivityDI position their title text at meaningfully
 * different places on screen, confirmed against two real captures (see each
 * Crop constant's own comment). A single shared band was tried first
 * (2026-09-08) and rejected: tight enough for one platform cut off the
 * other's title entirely (ML Kit returning zero text, not just the wrong
 * text - confirmed via live logcat on a real ONN 4K Pro), and a band wide
 * enough for both stopped being a meaningful calibration at all. Selecting
 * per-platform via FireTvSupport.isFireTv() costs nothing extra - the
 * capture pipeline already needs a Context by the time cropping happens.
 */
final class OcrCaptureConfig {

    private OcrCaptureConfig() {
    }

    // Downscaled capture resolution, set directly on the ImageReader /
    // VirtualDisplay at creation time - deliberately NOT captured at native
    // panel resolution and downscaled afterward, since that would pay both
    // the capture cost and the ML Kit inference cost at full size for no
    // benefit. RAISED to 1280x720 (2026-09-07) from 640x360: at 360p a
    // real Fire TV capture read the title/synopsis noticeably garbled
    // ("milliona ire", "D:arrry D:Viln") - too small for the Latin
    // recognizer. 720p is half of 1080p, so a title rendered ~65px on a
    // 1080p panel is ~43px here, comfortably readable. Still only the
    // capture/downscale size; ML Kit only ever sees the small cropped title
    // band below (~0.58 wide x ~0.115 tall of this), so inference stays
    // cheap even at the higher capture resolution.
    static final int CAPTURE_WIDTH = 1280;
    static final int CAPTURE_HEIGHT = 720;

    // 2x upscale of the cropped title band before OCR (2026-09-09) - fixed a
    // real k/l misread ("The Babadook" -> "The Babadool").
    static final float CROP_UPSCALE_FACTOR = 2.0f;

    /** Fractional crop band (applied to the already-downscaled bitmap - see OcrCaptureForegroundService.cropToConfig). */
    static final class Crop {
        final float left;
        final float top;
        final float right;
        final float bottom;

        private Crop(float left, float top, float right, float bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    // Google TV (EntityActivity).
    //
    // VERTICAL BAND CORRECTED 2026-09-09 - the previous top=0.15/bottom=0.42
    // band (dated 2026-08-25, "calibrated against a real captured
    // screenshot") was never actually confirmed against a real OCR read
    // through this pipeline end-to-end, unlike Fire TV mode's crop. Found
    // wrong while debugging "OCR always reads garbage/nothing regardless of
    // which movie is showing" (real symptom: consistently "TDL" or
    // NO_TEXT_FOUND for every title): saved the actual bitmap this pipeline
    // captures and crops (temporary debug instrumentation, since removed)
    // and looked at it directly instead of guessing from OCR text. On a real
    // capture (ONN, 1280x720 - this class's own CAPTURE_WIDTH/HEIGHT,
    // "Superbad"), the title sits at roughly y=252-360px (~0.35-0.50
    // vertically) with the rating/genre/year row starting at ~0.54 - the old
    // bottom=0.42 cut straight through the middle of the title text, feeding
    // ML Kit only the top halves of the letters. That's a garbage input
    // regardless of which title is on screen, which is exactly the
    // movie-independent symptom that gave this away. Whether the old
    // fractional values were simply wrong from the start, or the launcher's
    // real layout shifted sometime between 2026-08-25 and now, wasn't worth
    // chasing - what matters is this is now grounded in a real, inspected
    // capture, not a screenshot's claimed coordinates. New band (top=0.30,
    // bottom=0.52) has margin above and below the measured title bounds and
    // stops before the rating row.
    //
    // RIGHT widened 0.85 -> 0.97 on 2026-08-25, still valid, unaffected by
    // the vertical correction above: a long title ("The Fast and the
    // Furious: Tokyo Drift", live voice-search capture) OCR'd as "...Tokyo
    // Drit" - the rest of the string came through perfectly clean, only the
    // final letter of the last word was missing, right at the crop's old
    // right edge. That's the signature of the crop clipping the tail of a
    // long title, not a recognition-quality issue. The right-hand background
    // past the text is just hero art (no text to false-positive on), so
    // widening this further costs nothing; 0.97 rather than 1.0 keeps a
    // sliver of margin from the absolute edge.
    private static final Crop GOOGLE_TV_CROP = new Crop(0.0f, 0.30f, 0.97f, 0.52f);

    // Fire TV (DetailsPageDeepLinkActivityDI) - calibrated 2026-09-07 against
    // a real Fire TV Stick capture ("Ruthless People"): title sits at
    // roughly y=183-252px / x=105-548px out of 1920x1080 (~0.17-0.23
    // vertically, ~0.055-0.285 horizontally), with the rating/genre/year row
    // starting at ~0.25 just below it. This band targets the title only,
    // stopping above the rating row, with horizontal room (RIGHT=0.60) for a
    // longer title while keeping the far-right hero art out.
    private static final Crop FIRE_TV_CROP = new Crop(0.02f, 0.13f, 0.60f, 0.245f);

    /**
     * Picks the crop band for the device actually being captured on - see
     * this class's doc for why a single shared band doesn't work. Cheap:
     * FireTvSupport.isFireTv() is a single system-feature/manufacturer
     * check, not a network or disk call.
     */
    static Crop cropFor(Context context) {
        return FireTvSupport.isFireTv(context) ? FIRE_TV_CROP : GOOGLE_TV_CROP;
    }
}
