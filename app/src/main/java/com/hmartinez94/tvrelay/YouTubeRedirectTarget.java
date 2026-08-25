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
    SMARTTUBE("SmartTube"),
    TIZENTUBE_COBALT("TizenTube Cobalt");

    private final String label;

    YouTubeRedirectTarget(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
