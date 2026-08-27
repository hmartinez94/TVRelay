package com.hmartinez94.tvrelay;

/**
 * The app a resolved title gets opened in.
 *
 * NUVIO, STREMIO, and WUPLAY get a direct content deep link (see
 * PlayerLauncher.open()/prepare()) - WuPlay's `wuplay://{movie|series}/{imdbId}`
 * scheme confirmed working on-device 2026-08-24, added in WuPlay's own
 * v0.8.3-beta release the same day (see CLAUDE.md's "WuPlay wall" - this
 * reverses that section's original finding, which is why it's dated and
 * kept rather than deleted). JELLYFIN and WHOLPHIN have no
 * *universal-catalog* content deep link - see CLAUDE.md's "Plex and
 * Jellyfin" and "Wholphin support" notes - so they keep LaunchStyle.
 * TITLE_SEARCH and, by default, still get the same plain title search
 * hand-off Plex does (see PlayerLauncher.prepareTitleSearch()). Unlike
 * Plex, Jellyfin/Wholphin can *also* open a title directly - not via
 * usesTitleSearch()/LaunchStyle at all, but via a separate opt-in
 * (Preferences.isJellyfinLibraryLookupReady(), a server URL + API key set
 * in JellyfinSettingsStepFragment) that PlayerLauncher.planTitleSearch()
 * checks before ever falling back to prepareTitleSearch() - see that
 * method's javadoc and usesJellyfinServer() below. LaunchStyle.TITLE_SEARCH
 * staying put here is deliberate: it's still what every *other* caller
 * (usesTitleSearch(), MetadataResolver-bypass in the accessibility
 * service/SearchStepFragment) needs to know, and correctly describes
 * Jellyfin/Wholphin's default/fallback behavior - only planTitleSearch()
 * needs to know about the opt-in on top.
 *
 * WHOLPHIN (`com.github.damontecres.wholphin`) is a separate, from-scratch
 * (not forked) open-source Android TV client for a Jellyfin server - added
 * 2026-08-26, see CLAUDE.md's "Wholphin support" section for the research.
 * Its `itemId` is literally the same server-local item UUID Jellyfin's own
 * app and JellyfinClient's /Items search use, so it reuses the exact same
 * opt-in library-lookup config as JELLYFIN rather than needing its own
 * server URL/API key screen.
 *
 * PLEX is disabled (enabled=false) rather than removed - see CLAUDE.md.
 * Its code (here and in PlayerLauncher) is left fully intact; only
 * SettingsStepFragment and Preferences.getSelectedApp() actually enforce
 * the disable, by skipping/falling-back on a disabled entry. Re-enable by
 * flipping this flag back to true if that's ever warranted again.
 */
public enum PlayerApp {
    NUVIO("com.nuvio.app", "Nuvio", LaunchStyle.DEEP_LINK, 0, true),
    STREMIO("com.stremio.one", "Stremio", LaunchStyle.DEEP_LINK, 0, true),
    WUPLAY("app.wuplay.androidtv", "WuPlay", LaunchStyle.DEEP_LINK, 0, true),
    PLEX("com.plexapp.android", "Plex", LaunchStyle.TITLE_SEARCH, R.string.settings_player_plex_description, false),
    JELLYFIN("org.jellyfin.androidtv", "Jellyfin", LaunchStyle.TITLE_SEARCH, R.string.settings_player_jellyfin_description, true),
    WHOLPHIN("com.github.damontecres.wholphin", "Wholphin", LaunchStyle.TITLE_SEARCH, R.string.settings_player_wholphin_description, true);

    enum LaunchStyle {
        DEEP_LINK,
        TITLE_SEARCH
    }

    private final String packageName;
    private final String label;
    private final LaunchStyle launchStyle;
    private final int descriptionRes; // 0 = no description shown in Settings
    private final boolean enabled;

    PlayerApp(String packageName, String label, LaunchStyle launchStyle, int descriptionRes, boolean enabled) {
        this.packageName = packageName;
        this.label = label;
        this.launchStyle = launchStyle;
        this.descriptionRes = descriptionRes;
        this.enabled = enabled;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getLabel() {
        return label;
    }

    /** True for players that only accept a title search hand-off (Plex, Jellyfin) - see PlayerLauncher.prepareTitleSearch(). */
    boolean usesTitleSearch() {
        return launchStyle == LaunchStyle.TITLE_SEARCH;
    }

    /** 0 when this player needs no extra explanation in the Settings radio row. */
    int getDescriptionRes() {
        return descriptionRes;
    }

    /** False for a player kept in the codebase but not currently offered - see PLEX above and CLAUDE.md. */
    boolean isEnabled() {
        return enabled;
    }

    /**
     * True for a player that connects to the user's own Jellyfin server and
     * so can use the shared opt-in "find it in your library first" direct-
     * open path (Preferences.isJellyfinLibraryLookupReady() /
     * JellyfinClient) - JELLYFIN itself, and WHOLPHIN, an alternate
     * front-end for the same server type with the same server-local item
     * id contract. See PlayerLauncher.planTitleSearch()/openServerItem().
     */
    boolean usesJellyfinServer() {
        return this == JELLYFIN || this == WHOLPHIN;
    }
}
