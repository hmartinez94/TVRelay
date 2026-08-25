package com.hmartinez94.tvrelay;

/**
 * Tunable constants for the OCR capture fallback pipeline (see
 * OcrCaptureManager / OcrCaptureForegroundService / OcrTextCleaner). Every
 * value here is a blind guess pending real on-device calibration - nothing
 * in this class has been tested against an actual captured frame yet.
 * Same epistemic status as YOUTUBE_MARKERS in TvRelayAccessibilityService:
 * safe to ship because a bad guess just means OCR silently finds nothing
 * useful (see OcrCaptureManager.FailureReason.NO_TEXT_FOUND), never a
 * regression of anything else.
 */
final class OcrCaptureConfig {

    private OcrCaptureConfig() {
    }

    // Downscaled capture resolution, set directly on the ImageReader /
    // VirtualDisplay at creation time - deliberately NOT captured at native
    // panel resolution and downscaled afterward, since that would pay both
    // the capture cost and the ML Kit inference cost at full size for no
    // benefit. 640x360 is roughly 1/6 the pixel count of a 4K panel and
    // about 1/3 scale of 1080p, which cuts capture cost and ML Kit
    // inference cost together, at the expense of text sharpness (untested
    // trade-off - if OCR accuracy turns out too poor at this size once
    // calibrated against a real device, raise this before touching
    // anything else).
    static final int CAPTURE_WIDTH = 640;
    static final int CAPTURE_HEIGHT = 360;

    // Fractional crop applied to the already-downscaled bitmap (see
    // OcrCaptureForegroundService.cropToConfig - crop happens AFTER
    // downscaling, converted to pixel offsets against CAPTURE_WIDTH /
    // CAPTURE_HEIGHT, never against native resolution).
    //
    // CORRECTED 2026-08-25 against a real captured screenshot (ONN, 1920x1080,
    // EntityActivity detail page for "Superbad") - the original guess
    // (0.0/0.55/0.55/0.85) was wrong: that band lands squarely on the
    // "What it's about" synopsis box, not the title, which is exactly what
    // real OCR output confirmed (a garbled, misread synopsis sentence
    // instead of the title). On the real screenshot the title itself
    // ("Superbad", large text) sits at roughly y=230-380px / x=130-770px
    // out of 1920x1080 (~0.21-0.35 vertically, ~0.07-0.40 horizontally),
    // with the rating/genre/year row directly below it (~0.41-0.44) and the
    // "What it's about"/"What people are saying" boxes starting at ~0.49 -
    // this crop targets the title plus that rating row, stopping well
    // before the synopsis boxes, with a wide horizontal margin (RIGHT=0.85)
    // since a longer title than "Superbad" needs more room and the
    // right-hand background is just hero art (no text to false-positive
    // on). Still only confirmed against this one title/layout - if a
    // different card format positions its title elsewhere, this will need
    // another real-screenshot correction the same way this one was found
    // (see the OCR troubleshooting note in CLAUDE.md/the merge notes: a
    // wrong crop fails safe, producing garbled/NO_TEXT_FOUND output, never
    // a wrong regression elsewhere).
    static final float CROP_LEFT = 0.0f;
    static final float CROP_TOP = 0.15f;
    static final float CROP_RIGHT = 0.85f;
    static final float CROP_BOTTOM = 0.42f;
}
