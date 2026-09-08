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

    // Fractional crop applied to the already-downscaled bitmap (see
    // OcrCaptureForegroundService.cropToConfig - crop happens AFTER
    // downscaling, converted to pixel offsets against CAPTURE_WIDTH /
    // CAPTURE_HEIGHT, never against native resolution).
    //
    // RE-CORRECTED 2026-09-07 against a real Fire TV detail-page capture
    // (Fire TV Stick, 1920x1080, DetailsPageDeepLinkActivityDI for "Ruthless
    // People"). The previous band (0.0/0.15/0.85/0.42) was too tall: it
    // included the title AND the rating row AND the multi-line synopsis AND
    // the cast list, and OcrTextCleaner's old largest-AREA pick chose the
    // synopsis paragraph (big area) over the title (large font but short) -
    // confirmed by live logcat, where the OCR'd "title" came out as the
    // rating+synopsis+starring run-on and the real title never appeared.
    // Two fixes landed together: this tighter band, and OcrTextCleaner now
    // scoring by tallest font (see there). On the real screenshot the title
    // sits at ~y=183-252px / x=105-548px out of 1920x1080 (~0.17-0.23
    // vertically, ~0.055-0.285 horizontally) with the rating/genre/year row
    // starting at ~0.25 just below it. This band targets the title only,
    // stopping ABOVE the rating row (BOTTOM=0.245 < ~0.25), with horizontal
    // room (RIGHT=0.60) for a longer title and to keep the far-right hero
    // art out. The Google TV EntityActivity layout ("Superbad", the prior
    // calibration) positions its title lower (~0.21-0.35) - this narrower
    // Fire-TV-tuned band may clip that; the OCR fallback there is a Google
    // TV feature that a wrong crop just fails safe on (NO_TEXT_FOUND), and
    // the tallest-font picker below now does most of the work regardless of
    // the exact band. If Google TV OCR accuracy regresses, widen BOTTOM back
    // toward ~0.40 - the font-height picker keeps the title winning even
    // with the rating row back in frame.
    static final float CROP_LEFT = 0.02f;
    static final float CROP_TOP = 0.13f;
    static final float CROP_RIGHT = 0.60f;
    static final float CROP_BOTTOM = 0.245f;
}
