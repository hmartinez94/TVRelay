package com.hmartinez94.tvrelay;

/**
 * The app a resolved title gets opened in.
 *
 * NUVIO and STREMIO get a direct content deep link (see
 * PlayerLauncher.open()/prepare()). PLEX and JELLYFIN have no usable
 * content deep link - see CLAUDE.md's "Plex and Jellyfin" notes, and the
 * Cinemeta/OpenTVBridge investigation it's based on - so they only ever get
 * a plain title search hand-off (see PlayerLauncher.prepareTitleSearch()).
 * That's the same limitation that got Kodi support removed entirely: it
 * only searches whatever's already in the user's own library/server, not
 * an online catalog like Nuvio/Stremio - kept here (unlike Kodi) because
 * it's still honestly labeled as a search, not presented as a direct open.
 */
public enum PlayerApp {
    NUVIO("com.nuvio.app", "Nuvio", LaunchStyle.DEEP_LINK, 0),
    STREMIO("com.stremio.one", "Stremio", LaunchStyle.DEEP_LINK, 0),
    PLEX("com.plexapp.android", "Plex", LaunchStyle.TITLE_SEARCH, R.string.settings_player_plex_description),
    JELLYFIN("org.jellyfin.androidtv", "Jellyfin", LaunchStyle.TITLE_SEARCH, R.string.settings_player_jellyfin_description);

    enum LaunchStyle {
        DEEP_LINK,
        TITLE_SEARCH
    }

    private final String packageName;
    private final String label;
    private final LaunchStyle launchStyle;
    private final int descriptionRes; // 0 = no description shown in Settings

    PlayerApp(String packageName, String label, LaunchStyle launchStyle, int descriptionRes) {
        this.packageName = packageName;
        this.label = label;
        this.launchStyle = launchStyle;
        this.descriptionRes = descriptionRes;
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
}
