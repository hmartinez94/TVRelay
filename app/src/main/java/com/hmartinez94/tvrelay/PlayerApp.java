package com.hmartinez94.tvrelay;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The app a resolved title gets opened in, and everything PlayerLauncher
 * needs to know to do it - each constant's own line is the actual contract data
 * (packages tried in order, deep-link URI templates, or a title-search
 * component/server-item shape), not just a label. This replaced a set of
 * switch(app) statements scattered across PlayerLauncher (2026-09-08
 * refactor) - see that class's history if this ever needs reverting; no
 * behavior change was intended, only where the per-player facts live.
 *
 * NUVIO, STREMIO, and WUPLAY get a direct content deep link - WuPlay's
 * `wuplay://{movie|series}/{imdbId}` scheme confirmed working on-device
 * 2026-08-24, added in WuPlay's own v0.8.3-beta release the same day (see
 * CLAUDE.md's "WuPlay wall" - this reverses that section's original
 * finding, which is why it's dated and kept rather than deleted). JELLYFIN
 * and WHOLPHIN have no *universal-catalog* content deep link - see
 * CLAUDE.md's "Plex and Jellyfin" and "Wholphin support" notes - so they
 * keep LaunchStyle.TITLE_SEARCH and, by default, still get the same plain
 * title search hand-off Plex does (see PlayerLauncher.prepareTitleSearch()).
 * Unlike Plex, Jellyfin/Wholphin can *also* open a title directly - not via
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
 * NUVIO carries two packages, not one - see CLAUDE.md's "Nuvio dual-package
 * fallback": NuvioMedia/NuvioTV ships a Play Store build (com.nuvio.app)
 * and a differently-packaged GitHub Releases build (com.nuvio.tv) from the
 * same source, both accepting byte-identical nuvio:// URIs. PlayerLauncher
 * tries every entry in getPackages() in order, falling back generically
 * only past the last one - see openAcrossPackages().
 *
 * WHOLPHIN (`com.github.damontecres.wholphin`) is a separate, from-scratch
 * (not forked) open-source Android TV client for a Jellyfin server - added
 * 2026-08-26, see CLAUDE.md's "Wholphin support" section for the research.
 * Its `itemId` is literally the same server-local item UUID Jellyfin's own
 * app and JellyfinClient's /Items search use, so it reuses the exact same
 * opt-in library-lookup config as JELLYFIN rather than needing its own
 * server URL/API key screen - only its ServerItemStyle (how the item id
 * gets passed - Intent data vs. an "itemId" extra) actually differs.
 *
 * PLEX is disabled (enabled=false) rather than removed - see CLAUDE.md.
 * Its code (here and in PlayerLauncher) is left fully intact; only
 * SettingsStepFragment and Preferences.getSelectedApp() actually enforce
 * the disable, by skipping/falling-back on a disabled entry. Re-enable by
 * flipping this flag back to true if that's ever warranted again.
 */
public enum PlayerApp {
    // Deep-link players: movieUriTemplate/seriesUriTemplate are format
    // strings with one %s placeholder for the IMDb id - see
    // PlayerLauncher.open(). Nuvio alone also gets a tmdbUriTemplate (two
    // %s placeholders: TMDB's own media-path segment, then the TMDB id -
    // see prepare()'s TMDB-native fast path) and a second package.
    NUVIO(Arrays.asList("com.nuvio.app", "com.nuvio.tv"), "Nuvio", 0, true,
            "nuvio://movie/%s", "nuvio://detail/tv/%s", "nuvio://tmdb/%s/%s"),
    STREMIO("com.stremio.one", "Stremio", 0, true,
            "stremio:///detail/movie/%s", "stremio:///detail/series/%s"),
    WUPLAY("app.wuplay.androidtv", "WuPlay", 0, true,
            "wuplay://movie/%s", "wuplay://series/%s"),

    // Title-search players: titleSearchComponent is the fully-qualified
    // Activity PlayerLauncher.prepareTitleSearch() targets explicitly (see
    // that method for why implicit-intent resolution isn't used). Jellyfin
    // and Wholphin also connect to the user's own server for the opt-in
    // direct-open path (see PlayerLauncher.planTitleSearch()/openServerItem());
    // Plex doesn't, so its usesJellyfinServer is false and serverItemStyle null.
    PLEX("com.plexapp.android", "Plex", R.string.settings_player_plex_description, false,
            "com.plexapp.plex.activities.SplashActivity", false, null),
    JELLYFIN("org.jellyfin.androidtv", "Jellyfin", R.string.settings_player_jellyfin_description, true,
            "org.jellyfin.androidtv.ui.startup.StartupActivity", true, ServerItemStyle.URI_DATA),
    WHOLPHIN("com.github.damontecres.wholphin", "Wholphin", R.string.settings_player_wholphin_description, true,
            "com.github.damontecres.wholphin.MainActivity", true, ServerItemStyle.ITEM_ID_EXTRA);

    enum LaunchStyle {
        DEEP_LINK,
        TITLE_SEARCH
    }

    /**
     * How a server-item id (see PlayerLauncher.openServerItem()) gets passed
     * to a Jellyfin-server player's Activity - the one thing that differs
     * between Jellyfin's and Wholphin's otherwise-identical direct-open
     * contract. URI_DATA: ACTION_VIEW with the id as the Intent's data (what
     * Jellyfin's own StartupActivity.openNextActivity() reads).
     * ITEM_ID_EXTRA: ACTION_VIEW with the id in an "itemId" extra (Wholphin's
     * own documented Intents.md contract).
     */
    enum ServerItemStyle {
        URI_DATA,
        ITEM_ID_EXTRA
    }

    private final List<String> packages;
    private final String label;
    private final LaunchStyle launchStyle;
    private final int descriptionRes; // 0 = no description shown in Settings
    private final boolean enabled;
    private final String movieUriTemplate;
    private final String seriesUriTemplate;
    private final String tmdbUriTemplate;
    private final String titleSearchComponent;
    private final boolean usesJellyfinServer;
    private final ServerItemStyle serverItemStyle;

    /** Single-package deep-link player with no TMDB-native fast path (Stremio, WuPlay). */
    PlayerApp(String packageName, String label, int descriptionRes, boolean enabled,
              String movieUriTemplate, String seriesUriTemplate) {
        this(Collections.singletonList(packageName), label, descriptionRes, enabled,
                movieUriTemplate, seriesUriTemplate, null);
    }

    /** Deep-link player with one or more packages to try in order, and an optional TMDB-native fast path (Nuvio). */
    PlayerApp(List<String> packages, String label, int descriptionRes, boolean enabled,
              String movieUriTemplate, String seriesUriTemplate, String tmdbUriTemplate) {
        this.packages = packages;
        this.label = label;
        this.launchStyle = LaunchStyle.DEEP_LINK;
        this.descriptionRes = descriptionRes;
        this.enabled = enabled;
        this.movieUriTemplate = movieUriTemplate;
        this.seriesUriTemplate = seriesUriTemplate;
        this.tmdbUriTemplate = tmdbUriTemplate;
        this.titleSearchComponent = null;
        this.usesJellyfinServer = false;
        this.serverItemStyle = null;
    }

    /** Title-search player (Plex, Jellyfin, Wholphin) - see PlayerLauncher.prepareTitleSearch()/openServerItem(). */
    PlayerApp(String packageName, String label, int descriptionRes, boolean enabled,
              String titleSearchComponent, boolean usesJellyfinServer, ServerItemStyle serverItemStyle) {
        this.packages = Collections.singletonList(packageName);
        this.label = label;
        this.launchStyle = LaunchStyle.TITLE_SEARCH;
        this.descriptionRes = descriptionRes;
        this.enabled = enabled;
        this.movieUriTemplate = null;
        this.seriesUriTemplate = null;
        this.tmdbUriTemplate = null;
        this.titleSearchComponent = titleSearchComponent;
        this.usesJellyfinServer = usesJellyfinServer;
        this.serverItemStyle = serverItemStyle;
    }

    /** The primary package - what every caller outside PlayerLauncher's multi-package handling wants. */
    public String getPackageName() {
        return packages.get(0);
    }

    /** Every package to try, in order - see PlayerLauncher.openAcrossPackages(). Single-element for every player but Nuvio. */
    List<String> getPackages() {
        return packages;
    }

    public String getLabel() {
        return label;
    }

    /** True for players that only accept a title search hand-off (Plex, Jellyfin, Wholphin) - see PlayerLauncher.prepareTitleSearch(). */
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

    /** Format string (one %s, the IMDb id) for a movie deep link - null for a title-search player. */
    String getMovieUriTemplate() {
        return movieUriTemplate;
    }

    /** Format string (one %s, the IMDb id) for a series deep link - null for a title-search player. */
    String getSeriesUriTemplate() {
        return seriesUriTemplate;
    }

    /** Format string (two %s: TMDB's media-path segment, then the TMDB id) for a TMDB-native fast path - null except for Nuvio. */
    String getTmdbUriTemplate() {
        return tmdbUriTemplate;
    }

    /** Fully-qualified Activity a title-search player's Intent targets explicitly - null for a deep-link player. */
    String getTitleSearchComponent() {
        return titleSearchComponent;
    }

    /** How this player expects a server-item id passed - null unless usesJellyfinServer(). */
    ServerItemStyle getServerItemStyle() {
        return serverItemStyle;
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
        return usesJellyfinServer;
    }
}
