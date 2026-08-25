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
    // Confirmed real, reproduced limitation (2026-08-25) - see
    // PlayerLauncher.prepareTizenTubeCobalt()'s javadoc for the full
    // evidence: this only works the first time TizenTube Cobalt is opened
    // after being fully closed, not while it's already running, and
    // there's no way for TVRelay to force another app to fully restart.
    // Only officially supported paired with TizenTube Bridge (see
    // README's Limitations section) - flagged here too since a user
    // picking this from the dropdown may not have read that far.
    TIZENTUBE_COBALT("TizenTube Cobalt", R.string.settings_youtube_target_tizentube_cobalt_description);

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
