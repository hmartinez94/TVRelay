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

    // Google TV (EntityActivity) - calibrated 2026-08-25 against a real
    // captured screenshot (ONN, 1920x1080, "Superbad"): title sits at
    // roughly y=230-380px / x=130-770px out of 1920x1080 (~0.21-0.35
    // vertically, ~0.07-0.40 horizontally), with the rating/genre/year row
    // directly below (~0.41-0.44) and the "What it's about"/"What people are
    // saying" boxes starting at ~0.49. This band targets the title plus that
    // rating row, stopping before the synopsis, with a wide horizontal
    // margin (RIGHT=0.85) for a longer title than "Superbad" - the
    // right-hand background is just hero art. RE-CONFIRMED as the correct
    // Google TV band 2026-09-08: a since-reverted attempt to also fit Fire
    // TV's tighter band into a single shared crop cut this one off entirely
    // (ML Kit found zero text, live on a real ONN) - don't merge the two
    // platforms' bands again without testing both afterward.
    private static final Crop GOOGLE_TV_CROP = new Crop(0.0f, 0.15f, 0.85f, 0.42f);

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
