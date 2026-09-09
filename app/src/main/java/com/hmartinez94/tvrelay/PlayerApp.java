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
 * `wuplay://{movie|series}/{imdbId}` scheme was confirmed working on-device
 * 2026-08-24, added in WuPlay's own v0.8.3-beta release that same day (an
 * earlier version of this app had briefly given up on WuPlay support before
 * that release added the scheme). WAKO (`app.wako`) is the same shape - its
 * `wako://media/{movie|show}/{tmdbId}` scheme was confirmed working
 * on-device 2026-09-08 - but is disabled (enabled=false), same as PLEX
 * below and for an unrelated reason: confirmed the same day, with a real
 * user watching live, that the deep link only works when Wako's process
 * isn't already running - a warm Wako silently reopens whatever it last
 * showed instead of navigating to the new title, proven with adb alone
 * (identical PID before and after a second deep link) so it's not
 * something PlayerLauncher's Intent construction could ever fix - no
 * third-party app can force another app's process to restart. Re-enable by
 * flipping this flag back to true if Wako's own app ever starts handling a
 * warm relaunch correctly. JELLYFIN and WHOLPHIN have no *universal-catalog*
 * content deep link, so they keep LaunchStyle.TITLE_SEARCH and, by default, still get the same plain
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
 * MOONFIN is a third usesJellyfinServer() player, added 2026-09-09, but
 * unlike Jellyfin/Wholphin it has no fallback search screen at all - its
 * Android app declares no ACTION_SEARCH filter and no moonfin://search
 * scheme (confirmed by reading its whole open-source tree), only a
 * moonfin://item?id=<itemId> / moonfin://play?id=<itemId> content deep link
 * (ACTION_VIEW, scheme "moonfin") accepting the same server-local item UUID
 * as Jellyfin/Wholphin. hasTitleSearchFallback() is false for MOONFIN
 * (titleSearchComponent left null) precisely to mark this: planTitleSearch()
 * skips prepareTitleSearch() entirely on a miss rather than let it throw,
 * and reports failure instead - see PlayerLauncher.planTitleSearch()/
 * prepare(). Practically, the library lookup opt-in isn't optional for
 * Moonfin the way it is for Jellyfin/Wholphin: with it off, or on a miss,
 * there is nothing useful left to do.
 *
 * NUVIO carries two packages, not one - NuvioMedia/NuvioTV ships a Play Store build (com.nuvio.app)
 * and a differently-packaged GitHub Releases build (com.nuvio.tv) from the
 * same source, both accepting byte-identical nuvio:// URIs. PlayerLauncher
 * tries every entry in getPackages() in order, falling back generically
 * only past the last one - see openAcrossPackages().
 *
 * WHOLPHIN (`com.github.damontecres.wholphin`) is a separate, from-scratch
 * (not forked) open-source Android TV client for a Jellyfin server - added
 * 2026-08-26. Its `itemId` is literally the same server-local item UUID Jellyfin's own
 * app and JellyfinClient's /Items search use, so it reuses the exact same
 * opt-in library-lookup config as JELLYFIN rather than needing its own
 * server URL/API key screen - only its ServerItemStyle (how the item id
 * gets passed - Intent data vs. an "itemId" extra) actually differs.
 *
 * MOONFIN (`org.moonfin.androidtv`, plus an `org.moonfin.androidtv.beta`
 * sideload variant tried second - see getPackages()) is a Flutter rewrite of
 * a Jellyfin client, also sharing the same opt-in library-lookup config and
 * server-local item id - added 2026-09-09. Its ServerItemStyle is a third
 * shape neither Jellyfin's nor Wholphin's: URI_TEMPLATE, a full custom-scheme
 * URI (moonfin://item?id=%s) with the item id substituted in, rather than
 * bare Intent data or an extra - see PlayerLauncher.openServerItem(). See the
 * javadoc above for why it has no title-search fallback at all.
 *
 * PLEX is disabled (enabled=false) rather than removed, at explicit user
 * request (no technical reason - the ACTION_SEARCH route in PlayerLauncher
 * is fully working). Its code (here and in PlayerLauncher) is left fully intact; only
 * SettingsStepFragment and Preferences.getSelectedApp() actually enforce
 * the disable, by skipping/falling-back on a disabled entry. Re-enable by
 * flipping this flag back to true if that's ever warranted again.
 */
public enum PlayerApp {
    // Deep-link players: movieUriTemplate/seriesUriTemplate are format
    // strings with one %s placeholder for the IMDb id - see
    // PlayerLauncher.open(). tmdbMovieUriTemplate/tmdbSeriesUriTemplate are
    // the same shape for a TMDB id instead (see prepare()'s TMDB-native
    // fast path) - a full URI each, type token included, deliberately NOT
    // "one template + a substituted media-type segment": Nuvio and Wako do
    // not share a type vocabulary (nuvio://tmdb/tv/... vs
    // wako://media/show/... - "show", singular, is the only form Wako
    // accepts; wako://media/tv/... and wako://media/series/... were both
    // tested on-device and confirmed failing), so feeding TMDB's own
    // "movie"/"tv" path segment straight into a template would silently
    // build a dead URI for Wako.
    NUVIO(Arrays.asList("com.nuvio.app", "com.nuvio.tv"), "Nuvio", 0, true,
            "nuvio://movie/%s", "nuvio://detail/tv/%s",
            "nuvio://tmdb/movie/%s", "nuvio://tmdb/tv/%s"),
    STREMIO("com.stremio.one", "Stremio", 0, true,
            "stremio:///detail/movie/%s", "stremio:///detail/series/%s"),
    WUPLAY("app.wuplay.androidtv", "WuPlay", 0, true,
            "wuplay://movie/%s", "wuplay://series/%s"),
    // TMDB-native ONLY - the first player here with no IMDb-based URI at
    // all. wako://media/{movie|show}/{tmdbId} was confirmed working
    // on-device 2026-09-08 (Iron Man tmdb 1726, Breaking Bad tmdb 1396);
    // wako://media/movie/imdb/tt... and wako://movie/tt... were both tested
    // and silently no-opped to Wako's home screen, so the null/null IMDb
    // pair below is a confirmed absence, not an unfinished TODO - see
    // PlayerLauncher.prepare()'s guard, which is what stops a
    // TheTVDB-sourced candidate reaching open() and throwing.
    // DISABLED (enabled=false, 2026-09-08) - see the class javadoc above:
    // the deep link only works on a cold start, confirmed with a real click
    // and independently reproduced via adb (identical process PID
    // before/after a warm relaunch). Not a TVRelay bug - nothing here needs
    // fixing, only Wako's own app does.
    WAKO(Collections.singletonList("app.wako"), "Wako", R.string.settings_player_wako_description, false,
            null, null,
            "wako://media/movie/%s", "wako://media/show/%s"),

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
            "com.github.damontecres.wholphin.MainActivity", true, ServerItemStyle.ITEM_ID_EXTRA),
    // No titleSearchComponent (null) - Moonfin has no search screen a third
    // party can open at all, unlike Jellyfin/Wholphin above. See
    // hasTitleSearchFallback() and the class javadoc.
    MOONFIN(Arrays.asList("org.moonfin.androidtv", "org.moonfin.androidtv.beta"), "Moonfin",
            R.string.settings_player_moonfin_description, true,
            null, true, ServerItemStyle.URI_TEMPLATE, "moonfin://item?id=%s");

    enum LaunchStyle {
        DEEP_LINK,
        TITLE_SEARCH
    }

    /**
     * How a server-item id (see PlayerLauncher.openServerItem()) gets passed
     * to a Jellyfin-server player's Activity - the one thing that differs
     * between Jellyfin's, Wholphin's, and Moonfin's otherwise-identical
     * direct-open contract. URI_DATA: ACTION_VIEW with the id as the Intent's
     * data (what Jellyfin's own StartupActivity.openNextActivity() reads).
     * ITEM_ID_EXTRA: ACTION_VIEW with the id in an "itemId" extra (Wholphin's
     * own documented Intents.md contract). URI_TEMPLATE: ACTION_VIEW on a
     * full custom-scheme URI built by substituting the id into
     * getServerItemUriTemplate() (Moonfin's moonfin://item?id=%s) - unlike
     * the other two styles this is targeted by package via
     * openAcrossPackages(), not an explicit component, since Moonfin's beta
     * sideload uses a different applicationId under the same class names.
     */
    enum ServerItemStyle {
        URI_DATA,
        ITEM_ID_EXTRA,
        URI_TEMPLATE
    }

    private final List<String> packages;
    private final String label;
    private final LaunchStyle launchStyle;
    private final int descriptionRes; // 0 = no description shown in Settings
    private final boolean enabled;
    private final String movieUriTemplate;
    private final String seriesUriTemplate;
    private final String tmdbMovieUriTemplate;
    private final String tmdbSeriesUriTemplate;
    private final String titleSearchComponent;
    private final boolean usesJellyfinServer;
    private final ServerItemStyle serverItemStyle;
    private final String serverItemUriTemplate;

    /** Single-package deep-link player with no TMDB-native fast path (Stremio, WuPlay). */
    PlayerApp(String packageName, String label, int descriptionRes, boolean enabled,
              String movieUriTemplate, String seriesUriTemplate) {
        this(Collections.singletonList(packageName), label, descriptionRes, enabled,
                movieUriTemplate, seriesUriTemplate, null, null);
    }

    /**
     * Deep-link player with one or more packages to try in order, and any
     * combination of IMDb-based and TMDB-native URI templates. Both pairs
     * are independently nullable: Nuvio has both, Stremio/WuPlay have only
     * the IMDb pair, Wako has only the TMDB pair (no working IMDb-based
     * wako:// URI exists - see WAKO above). PlayerLauncher.prepare() picks
     * between them per candidate and bails out cleanly when neither
     * applies.
     */
    PlayerApp(List<String> packages, String label, int descriptionRes, boolean enabled,
              String movieUriTemplate, String seriesUriTemplate,
              String tmdbMovieUriTemplate, String tmdbSeriesUriTemplate) {
        this.packages = packages;
        this.label = label;
        this.launchStyle = LaunchStyle.DEEP_LINK;
        this.descriptionRes = descriptionRes;
        this.enabled = enabled;
        this.movieUriTemplate = movieUriTemplate;
        this.seriesUriTemplate = seriesUriTemplate;
        this.tmdbMovieUriTemplate = tmdbMovieUriTemplate;
        this.tmdbSeriesUriTemplate = tmdbSeriesUriTemplate;
        this.titleSearchComponent = null;
        this.usesJellyfinServer = false;
        this.serverItemStyle = null;
        this.serverItemUriTemplate = null;
    }

    /** Single-package title-search player (Plex, Jellyfin, Wholphin) - see PlayerLauncher.prepareTitleSearch()/openServerItem(). */
    PlayerApp(String packageName, String label, int descriptionRes, boolean enabled,
              String titleSearchComponent, boolean usesJellyfinServer, ServerItemStyle serverItemStyle) {
        this(Collections.singletonList(packageName), label, descriptionRes, enabled,
                titleSearchComponent, usesJellyfinServer, serverItemStyle, null);
    }

    /**
     * Multi-package title-search player (Moonfin - stable + beta sideload
     * package, tried in order like Nuvio's two packages). serverItemUriTemplate
     * is only non-null for ServerItemStyle.URI_TEMPLATE, where it's a full
     * format string (one %s, the item id) - see PlayerLauncher.openServerItem().
     */
    PlayerApp(List<String> packages, String label, int descriptionRes, boolean enabled,
              String titleSearchComponent, boolean usesJellyfinServer, ServerItemStyle serverItemStyle,
              String serverItemUriTemplate) {
        this.packages = packages;
        this.label = label;
        this.launchStyle = LaunchStyle.TITLE_SEARCH;
        this.descriptionRes = descriptionRes;
        this.enabled = enabled;
        this.movieUriTemplate = null;
        this.seriesUriTemplate = null;
        this.tmdbMovieUriTemplate = null;
        this.tmdbSeriesUriTemplate = null;
        this.titleSearchComponent = titleSearchComponent;
        this.usesJellyfinServer = usesJellyfinServer;
        this.serverItemStyle = serverItemStyle;
        this.serverItemUriTemplate = serverItemUriTemplate;
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

    /** True for players that only accept a title search hand-off (Plex, Jellyfin, Wholphin, Moonfin) - see PlayerLauncher.prepareTitleSearch(). */
    boolean usesTitleSearch() {
        return launchStyle == LaunchStyle.TITLE_SEARCH;
    }

    /**
     * False for a title-search player with no search screen a third party
     * can open at all (Moonfin - see the class javadoc). PlayerLauncher.
     * planTitleSearch()/prepare() check this before ever calling
     * prepareTitleSearch(), which throws for a null titleSearchComponent.
     */
    boolean hasTitleSearchFallback() {
        return titleSearchComponent != null;
    }

    /** 0 when this player needs no extra explanation in the Settings radio row. */
    int getDescriptionRes() {
        return descriptionRes;
    }

    /** False for a player kept in the codebase but not currently offered - see PLEX/WAKO above. */
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

    /** Format string (one %s, the TMDB id) for a TMDB-native movie deep link - null for a player with no TMDB-native path. */
    String getTmdbMovieUriTemplate() {
        return tmdbMovieUriTemplate;
    }

    /** Format string (one %s, the TMDB id) for a TMDB-native series deep link - null for a player with no TMDB-native path. */
    String getTmdbSeriesUriTemplate() {
        return tmdbSeriesUriTemplate;
    }

    /** Fully-qualified Activity a title-search player's Intent targets explicitly - null for a deep-link player. */
    String getTitleSearchComponent() {
        return titleSearchComponent;
    }

    /** How this player expects a server-item id passed - null unless usesJellyfinServer(). */
    ServerItemStyle getServerItemStyle() {
        return serverItemStyle;
    }

    /** Format string (one %s, the server item id) for ServerItemStyle.URI_TEMPLATE - null for every other style. */
    String getServerItemUriTemplate() {
        return serverItemUriTemplate;
    }

    /**
     * True for a player that connects to the user's own Jellyfin server and
     * so can use the shared opt-in "find it in your library first" direct-
     * open path (Preferences.isJellyfinLibraryLookupReady() /
     * JellyfinClient) - JELLYFIN itself, WHOLPHIN, and MOONFIN, alternate
     * front-ends for the same server type with the same server-local item
     * id contract. See PlayerLauncher.planTitleSearch()/openServerItem().
     */
    boolean usesJellyfinServer() {
        return usesJellyfinServer;
    }
}
