package com.hmartinez94.tvrelay;

/**
 * Which sideloaded YouTube TV client the YouTube-redirect toggle
 * (Preferences.isSmartTubeEnabled()) targets when it's on - see
 * SettingsStepFragment and PlayerLauncher.prepareYouTubeRedirect().
 *
 * An explicit user choice via a Settings dropdown, not auto-detected
 * install-state guessing - the original 2026-08-24 design picked whichever
 * of the two was detected installed (preferring SmartTube), which meant a
 * user with both installed had no way to actually choose TizenTube Cobalt.
 * Replaced 2026-08-25 by user request: which app they actually want is
 * something only they know, not something to infer.
 */
public enum YouTubeRedirectTarget {
    SMARTTUBE("SmartTube", 0),
    // Used to only work on a cold start (Cobalt fully closed); fixed by
    // launching without CLEAR_TASK - see PlayerLauncher.prepareTizenTubeCobalt().
    TIZENTUBE_COBALT("TizenTube Cobalt", 0);

    private final String label;
    private final int descriptionRes; // 0 = no description shown in the dropdown

    YouTubeRedirectTarget(String label, int descriptionRes) {
        this.label = label;
        this.descriptionRes = descriptionRes;
    }

    public String getLabel() {
        return label;
    }

    /** 0 when this target needs no extra explanation in the Settings dropdown. */
    int getDescriptionRes() {
        return descriptionRes;
    }
}
