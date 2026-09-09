package com.hmartinez94.tvrelay;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Opens a title in whichever app the user picked in Settings.
 *
 * The per-player facts this needs (deep-link URI templates, packages to
 * try, title-search component, server-item shape) live on PlayerApp itself
 * (2026-09-08 refactor - see that enum's class doc) rather than being
 * spread across switch(app) statements here; this class is now mostly
 * generic dispatch (open()/prepareTitleSearch()/openServerItem()/
 * openAcrossPackages()) plus the handful of things that genuinely aren't
 * per-player data - resolution order in prepare(), the Jellyfin-library
 * opt-in in planTitleSearch(), and the unrelated SmartTube/TizenTube
 * YouTube-redirect toggle below.
 *
 * Plex / Jellyfin / Wholphin have no *universal-catalog* content deep link
 * (see PlayerApp's class doc), so all three default to a
 * plain title search hand-off via prepareTitleSearch(). Jellyfin and
 * Wholphin pre-fill the query; Plex's ACTION_SEARCH route (the only working
 * one, confirmed by decompiling Plex's own app) opens Plex's real search
 * screen but does NOT pre-fill it, a confirmed gap in Plex's own app, not
 * something fixable here. (Plex is currently disabled - not removed - see
 * PlayerApp.isEnabled().) Title search never touches MetadataResolver at
 * all - see TvRelayAccessibilityService and SearchStepFragment, which route
 * through planTitleSearch() before ever calling resolveCandidates().
 *
 * Jellyfin and Wholphin specifically also support a *direct* open - see
 * planTitleSearch()/openServerItem() - gated behind Preferences.
 * isJellyfinLibraryLookupReady(), an opt-in that needs a server URL + API
 * key (JellyfinSettingsStepFragment). When on and the clicked title is an
 * exact match in the user's own library, TVRelay opens that title's detail
 * page directly, the same way Nuvio/Stremio/WuPlay/Wako do; otherwise (feature
 * off, not configured, or no library hit) it falls through to the plain
 * search hand-off above, unchanged. Jellyfin's /Items endpoint has no
 * provider-id filter exposed (see JellyfinClient's class doc), so this
 * match is title-based, same exact-match handling TMDB/TheTVDB already use.
 * Wholphin (com.github.damontecres.wholphin) is a separate, from-scratch
 * open-source Jellyfin client, not an official Jellyfin build - it shares
 * this same opt-in config because it connects to the same kind of server
 * and uses the same server-local item id, not because it's built from
 * Jellyfin's code (see PlayerApp's class doc). openServerItem() picks which of the two players' own Intent
 * contract to use for a given item id.
 *
 * Takes a plain Context (not specifically AccessibilityService) so it can be
 * called both from the accessibility service and from a regular Activity
 * (the manual search fallback - see SearchStepFragment).
 */
final class PlayerLauncher {

    private static final String TAG = "PlayerLauncher";

    // SmartTube: a sideloaded YouTube TV client, unrelated to PlayerApp -
    // see prepareSmartTube() and Preferences.isSmartTubeEnabled(). Two
    // separate distributions exist; try stable first, then beta.
    private static final String SMARTTUBE_LABEL = "SmartTube";
    private static final String SMARTTUBE_STABLE_PACKAGE = "org.smarttube.stable";
    private static final String SMARTTUBE_BETA_PACKAGE = "org.smarttube.beta";

    // TizenTube Cobalt (reisxd/TizenTubeCobalt, https://tizentube.app): a
    // real, separate, actively-maintained Android TV port of the well-known
    // "TizenTube" ad-block mod - NOT the original TizenTube itself, which is
    // confirmed Tizen-OS-only (Samsung Smart TVs, installed via TizenBrew)
    // and cannot run on Android TV at all. Built on Google's own open-source
    // Cobalt HTML5-app-container runtime (the same tech YouTube's own
    // official TV app historically used). Added 2026-08-25 as a second
    // fallback target for the same YouTube-redirect toggle, alongside
    // SmartTube - see prepareYouTubeRedirect() for how the two are chosen
    // between. Confirmed real and functional on the ONN test device, not
    // just a hopeful guess:
    //  - v2.0.2's `cobalt-arm.apk` release asset installed via `adb install
    //    -r`; real installed package name confirmed via `adb shell pm list
    //    packages | grep cobalt`.
    //  - `adb shell dumpsys package io.gh.reisxd.tizentube.cobalt` shows a
    //    real ACTION_VIEW/MEDIA_PLAY_FROM_SEARCH intent-filter (Category:
    //    DEFAULT, BROWSABLE) for youtube.com/www.youtube.com/m.youtube.com/
    //    youtu.be with a GLOB ".*" path pattern - the exact same URL contract
    //    SmartTube already uses (see buildYouTubeSearchUri() below) - plus
    //    separate LAUNCHER/LEANBACK_LAUNCHER/DEFAULT categories confirming
    //    it's a real, launcher-visible Android TV (leanback) app.
    //  - `adb shell am start -a android.intent.action.VIEW -d
    //    "https://www.youtube.com/results?search_query=..." -p
    //    io.gh.reisxd.tizentube.cobalt" was confirmed to actually focus and
    //    run the app (dumpsys window mFocusedApp, pidof, and a clean logcat
    //    with no FATAL/AndroidRuntime exceptions) - not just "no crash".
    // No stable/beta split exists for this app (checked release history back
    // to v1.0.2) - only one package to try, unlike SmartTube.
    private static final String TIZENTUBE_COBALT_LABEL = "TizenTube Cobalt";
    private static final String TIZENTUBE_COBALT_PACKAGE = "io.gh.reisxd.tizentube.cobalt";

    private PlayerLauncher() {
    }

    /** True when the selected player only needs a plain title search hand-off (Plex, Jellyfin) - see PlayerApp.usesTitleSearch(). */
    static boolean usesTitleSearch(Context context) {
        return Preferences.getSelectedApp(context).usesTitleSearch();
    }

    /**
     * Resolves (if needed) and opens a candidate in one step. For Nuvio with
     * a TMDB-provenance candidate, or for a title-search player, this needs
     * no network call at all - see prepare(). Otherwise this is a blocking
     * network call for TMDB (the external_ids fetch) - call only from a
     * background thread. Returns whether it succeeded: either resolution
     * failed, or the target app couldn't actually be launched (e.g. not
     * installed) - see openWithFallback().
     */
    static boolean openCandidate(Context context, TitleCandidate candidate) {
        BooleanSupplier launch = prepare(context, candidate);
        return launch != null && launch.getAsBoolean();
    }

    /**
     * Returns a BooleanSupplier that opens this candidate when invoked (and
     * reports whether the target app actually launched), or null if it
     * couldn't be resolved - without actually opening anything yet. Exists
     * so a caller can decide to open it later (e.g. only once a confirm
     * button is tapped - see TvRelayAccessibilityService) while still doing
     * any necessary network resolution up front, on the same background
     * thread it already had to be on.
     *
     * For a title-search player, nothing needs resolving either - see
     * prepareTitleSearch(). For a TMDB-native player (Nuvio, Wako) + a
     * TMDB-provenance candidate, likewise nothing needs resolving: both
     * accept a TMDB id directly (nuvio://tmdb/{movie|tv}/{id},
     * wako://media/{movie|show}/{id}), so the returned supplier is a plain,
     * already-built Intent launch with no network call in it at all -
     * faster, and it works even for a candidate TMDB hasn't cross-referenced
     * to an IMDb id yet (see TmdbClient.resolveImdbId, which would
     * otherwise fail those). Every other case (TheTVDB candidates, or
     * Stremio/WuPlay - which have no TMDB-native scheme) resolves an IMDb id
     * first, exactly as before this existed - a blocking network call for
     * TMDB in that case.
     *
     * Returns null - "nothing to open", handled by every caller already
     * (see TitleHandler/SearchStepFragment) - not only when resolution
     * fails, but also for the one combination with no route at all: a
     * TheTVDB-sourced candidate with Wako selected, since Wako has no
     * IMDb-based URI (see PlayerApp.WAKO). That's checked before the
     * resolve call, so it costs nothing.
     */
    static BooleanSupplier prepare(Context context, TitleCandidate candidate) {
        if (candidate.jellyfinItemId != null) {
            // A hit from the user's own Jellyfin library (see
            // JellyfinClient/planTitleSearch()) - already directly
            // launchable by item id. Checked before app.usesTitleSearch()
            // below: Jellyfin/Wholphin's LaunchStyle stays TITLE_SEARCH even
            // with library lookup on (see PlayerApp), so without this check
            // an already-resolved candidate from the chooser/manual-search
            // path would fall into prepareTitleSearch() and re-search by
            // title instead of opening what the user actually picked.
            PlayerApp libraryApp = Preferences.getSelectedApp(context);
            return () -> openServerItem(context, libraryApp, candidate.jellyfinItemId);
        }
        PlayerApp app = Preferences.getSelectedApp(context);
        if (app.usesTitleSearch()) {
            return prepareTitleSearch(context, candidate.displayTitle);
        }
        String tmdbTemplate = forType(candidate.type, app.getTmdbMovieUriTemplate(), app.getTmdbSeriesUriTemplate());
        if (tmdbTemplate != null && candidate.tmdbMediaPath != null) {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(String.format(tmdbTemplate, candidate.tmdbId)));
            return () -> openAcrossPackages(context, intent, app);
        }

        // No TMDB-native route for this candidate, so the only route left is
        // an IMDb id - which WAKO has none of at all (confirmed absence, see
        // PlayerApp.WAKO). Checked before MetadataResolver.resolve()
        // so this costs zero network calls; returns null exactly like any
        // other unresolvable title (see this method's javadoc / callers).
        if (forType(candidate.type, app.getMovieUriTemplate(), app.getSeriesUriTemplate()) == null) {
            Log.w(TAG, app + " has no deep link for this candidate (no TMDB id, no IMDb-based "
                    + app + " URI): " + candidate.displayTitle);
            return null;
        }

        TvdbMatch match = MetadataResolver.resolve(context, candidate);
        return match != null ? () -> open(context, match) : null;
    }

    /**
     * Builds (but doesn't run) a search hand-off for a title-search player
     * (Plex, Jellyfin) - see PlayerApp's class doc. Neither has a usable
     * content deep link, so this just opens the player's own search screen -
     * pre-filled with the clicked/typed title for Jellyfin, but NOT for
     * Plex (see the PLEX case below - confirmed via decompilation to be a
     * gap in Plex's own app, not fixable from here). No network call, so
     * unlike the IMDb/TMDB paths this can't fail to "resolve" - the only
     * way the returned supplier can report false is if the target app
     * itself couldn't be launched (e.g. not installed).
     */
    static BooleanSupplier prepareTitleSearch(Context context, String rawTitle) {
        PlayerApp app = Preferences.getSelectedApp(context);
        String title = TitleCleanup.stripTrailingParentheticals(rawTitle);

        // Every title-search player (Plex, Jellyfin, Wholphin) builds the
        // exact same ACTION_SEARCH + query-extra shape, targeting its own
        // explicit component (see PlayerApp.getTitleSearchComponent()'s
        // javadoc for why an explicit component, not implicit resolution).
        // NUVIO/STREMIO/WUPLAY never reach here - prepare() only calls this
        // method when app.usesTitleSearch() is true.
        if (app.getTitleSearchComponent() == null) {
            throw new IllegalStateException("prepareTitleSearch() called for a non-search player: " + app);
        }
        Intent intent = new Intent(Intent.ACTION_SEARCH);
        intent.putExtra(SearchManager.QUERY, title);
        intent.setClassName(app.getPackageName(), app.getTitleSearchComponent());
        // Note for Plex specifically: this reliably opens Plex's real search
        // screen but does NOT pre-fill the query - traced via decompilation
        // all the way through Plex's own internal SearchActivity -> ... ->
        // c2() relay, which correctly forwards "query" right up until the
        // final hand-off to MobileSearchActivity, which doesn't consume it -
        // a real bug in Plex's own app on this build, not something wrong on
        // our end. Still sent (harmless, and free if Plex ever fixes it)
        // because this is still better than the dead https://watch.plex.tv/
        // search?q= URL it replaced, confirmed by decompiling Plex's own
        // search-handoff path. Jellyfin and Wholphin's own
        // ACTION_SEARCH handling doesn't have this gap; both pre-fill
        // correctly.

        // Dropping setPackage on a packageless retry would behave very
        // differently here than it does for Nuvio/Stremio's custom scheme:
        // Plex's https URL would silently open a browser instead of
        // reporting failure, and Jellyfin's ACTION_SEARCH has no sane
        // packageless target at all. Worse than just reporting failure, so
        // no generic fallback for search-style players - see
        // openWithFallback()'s allowGenericFallback param.
        return () -> openWithFallback(context, intent, app.getLabel(), false);
    }

    /**
     * Direct-open for a Jellyfin-server library hit (see planTitleSearch()),
     * for either Jellyfin or Wholphin - see PlayerApp.ServerItemStyle's
     * javadoc for the one way their otherwise-identical contract differs.
     * URI_DATA (Jellyfin): StartupActivity.openNextActivity() (Jellyfin's
     * own Kotlin source, confirmed via WebFetch 2026-08-26) accepts
     * ACTION_VIEW with intent.data.toString() fed straight into the SDK's
     * toUUIDOrNull(), documented to "accept simple and hyphenated
     * notations" - so the bare hex Id JellyfinClient's /Items search
     * returned works verbatim as the Intent data, no reformatting needed
     * (Uri.parse() of a bare id with no scheme yields exactly that string
     * back from toString()). ITEM_ID_EXTRA (Wholphin): its own Intents.md
     * documents ACTION_VIEW with an "itemId" extra instead - opens the same
     * detail page, not its separate immediate-playback PLAYBACK action
     * (deliberately not used here, to keep behavior consistent between the
     * two players rather than making Wholphin auto-play while Jellyfin only
     * opens a detail page). Both target their explicit component (see
     * getTitleSearchComponent()'s javadoc) with no packageless retry - a
     * malformed/stale id should report failure, not silently land somewhere
     * unhelpful.
     */
    private static boolean openServerItem(Context context, PlayerApp app, String itemId) {
        Intent intent;
        if (app.getServerItemStyle() == PlayerApp.ServerItemStyle.ITEM_ID_EXTRA) {
            intent = new Intent(Intent.ACTION_VIEW);
            intent.putExtra("itemId", itemId);
        } else {
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse(itemId));
        }
        intent.setClassName(app.getPackageName(), app.getTitleSearchComponent());
        return openWithFallback(context, intent, app.getLabel(), false);
    }

    /**
     * Bundles planTitleSearch()'s decision together with the already-built
     * launch it settled on - see YouTubeRedirect above for the same
     * "decision + launch, computed together" shape. candidates is only ever
     * non-empty when foundInLibrary is true, and is exactly what
     * MetadataResolver.isAmbiguous()/the match chooser already expect, so a
     * caller can reuse both unchanged for the ambiguous-match case.
     */
    static final class TitleSearchPlan {
        final boolean foundInLibrary;
        final List<TitleCandidate> candidates;
        final BooleanSupplier launch;

        private TitleSearchPlan(boolean foundInLibrary, List<TitleCandidate> candidates, BooleanSupplier launch) {
            this.foundInLibrary = foundInLibrary;
            this.candidates = candidates;
            this.launch = launch;
        }
    }

    /**
     * The entry point TvRelayAccessibilityService and SearchStepFragment use
     * instead of calling prepareTitleSearch() directly - decides whether the
     * clicked/typed title is an exact match in the user's own Jellyfin
     * library (only attempted when Preferences.isJellyfinLibraryLookupReady()
     * AND the selected player is one of the two that can use it - see
     * PlayerApp.usesJellyfinServer()) before falling back to exactly the
     * plain search hand-off prepareTitleSearch() alone used to produce.
     * Blocking (one Jellyfin request when the feature is on and set up) -
     * call only from a background thread, same as
     * MetadataResolver.resolveCandidates().
     *
     * For every player other than Jellyfin/Wholphin, or either of those with
     * the feature off/not fully configured, or a library search that finds
     * nothing exactly: foundInLibrary=false, candidates empty, launch=the
     * plain search hand-off - i.e. today's behavior, byte-identical, unless
     * the feature is deliberately turned on and set up.
     */
    static TitleSearchPlan planTitleSearch(Context context, String rawTitle) {
        PlayerApp app = Preferences.getSelectedApp(context);
        if (app.usesJellyfinServer() && Preferences.isJellyfinLibraryLookupReady(context)) {
            List<TitleCandidate> candidates = JellyfinClient.findLibraryCandidates(context, rawTitle);
            if (!candidates.isEmpty()) {
                TitleCandidate best = candidates.get(0);
                return new TitleSearchPlan(true, candidates, () -> openServerItem(context, app, best.jellyfinItemId));
            }
        }
        return new TitleSearchPlan(false, Collections.emptyList(), prepareTitleSearch(context, rawTitle));
    }

    /**
     * Shared by prepareSmartTube() and prepareTizenTubeCobalt() - both target
     * the exact same YouTube search URL contract
     * (https://www.youtube.com/results?search_query={title}), just a
     * different package. Extracted so the two paths can't drift apart the
     * way TitleSearchFallbacks was originally duplicated per-client and
     * grew real bugs from that duplication before being unified.
     */
    private static Uri buildYouTubeSearchUri(String rawTitle) {
        String title = TitleCleanup.stripTrailingParentheticals(rawTitle);
        return Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(title));
    }

    /**
     * SmartTube redirect for a YouTube video recommendation card - one of
     * two sideloaded YouTube TV clients this toggle can target (see
     * prepareYouTubeRedirect()) - entirely separate from the PlayerApp
     * movie/show player choice (a user can have Nuvio selected for movies
     * and still get YouTube videos redirected to SmartTube). See
     * Preferences.isSmartTubeEnabled() and TvRelayAccessibilityService's
     * YouTube-marker detection, which decides when this gets called at all -
     * this method itself doesn't check the setting. Opens a YouTube search
     * for the title (no direct video-id deep link attempted - no reliable
     * way to resolve a specific video id from just a title), trying
     * SmartTube's stable package first, then its beta
     * package if that fails. No packageless fallback for either attempt,
     * same reasoning as prepareTitleSearch(): a bare YouTube search URL
     * would otherwise silently open in a browser or the official YouTube
     * app instead of SmartTube, which defeats the point of this feature.
     */
    static BooleanSupplier prepareSmartTube(Context context, String rawTitle) {
        Uri uri = buildYouTubeSearchUri(rawTitle);
        return () -> {
            Intent stable = new Intent(Intent.ACTION_VIEW, uri).setPackage(SMARTTUBE_STABLE_PACKAGE);
            if (openWithFallback(context, stable, SMARTTUBE_LABEL, false)) {
                return true;
            }
            Intent beta = new Intent(Intent.ACTION_VIEW, uri).setPackage(SMARTTUBE_BETA_PACKAGE);
            return openWithFallback(context, beta, SMARTTUBE_LABEL, false);
        };
    }

    /**
     * TizenTube Cobalt redirect for a YouTube video recommendation card - the
     * second fallback target for the same toggle prepareSmartTube() serves;
     * see the TIZENTUBE_COBALT_* constants above for what confirms this is a
     * real, working target, and prepareYouTubeRedirect() for how the two are
     * chosen between. Single package attempt (no stable/beta split exists for
     * this app, unlike SmartTube), no packageless fallback for the same
     * reason as prepareSmartTube() and prepareTitleSearch(): a bare YouTube
     * search URL without setPackage could open a browser or the real YouTube
     * app instead, defeating the point of redirecting away from either.
     *
     * NOT the same action as SmartTube, despite sharing the same search URL
     * shape - confirmed real bug (2026-08-25): Intent.ACTION_VIEW with this
     * URL does launch TizenTube Cobalt (its manifest's intent-filter matches
     * it), but the app just opens to its own home feed and silently ignores
     * the search_query param - confirmed via a real on-device screenshot,
     * not just an unconfirmed am-start probe like the original integration
     * check. "android.media.action.MEDIA_PLAY_FROM_SEARCH" (declared in the
     * SAME intent-filter block per dumpsys - see TIZENTUBE_COBALT_PACKAGE's
     * dumpsys evidence) with the identical data URI is what actually works -
     * confirmed via another real screenshot: "Search results for sauteed
     * potatoes" with the real matching video as the top hit.
     *
     * UNRELIABLE ONCE COBALT IS ALREADY RUNNING - confirmed real, reproduced
     * limitation (2026-08-25), not a TVRelay bug: the exact same Intent that
     * correctly opens real search results on a genuine cold start (Cobalt's
     * process not already alive) instead lands on Cobalt's plain home feed,
     * silently ignoring the query, once Cobalt is already resident - even
     * though openWithFallback()'s FLAG_ACTIVITY_CLEAR_TASK does force a
     * fresh Activity/task (confirmed via its splash screen reappearing),
     * Cobalt's underlying Starboard/Cobalt runtime process apparently
     * persists underneath and ignores the new intent's data on that second
     * launch. Reproduced twice back-to-back with identical intents, only the
     * process's alive/dead state differed. TVRelay has no way to force
     * another app's process to fully restart - FORCE_STOP_PACKAGES is a
     * signature-level permission no third-party app can hold, sideloaded or
     * not.
     *
     * Per user decision (2026-08-25): TizenTube Cobalt as a YouTube-redirect
     * target is only officially supported for a user who has also installed
     * TizenTube Bridge (https://github.com/TobiPeterG/tizentube-bridge, a
     * separate sideloaded app that takes over the official YouTube TV
     * package id and forwards YouTube-targeted intents to Cobalt). NOT
     * verified to actually fix the warm-restart issue above - its own
     * README describes it as a pure intent pass-through ("It cannot make
     * Cobalt support a deep link that Cobalt itself does not understand"),
     * so this codebase's read is that the underlying limitation likely
     * still applies even with Bridge installed. Not tested directly:
     * Bridge requires uninstalling the device's official YouTube TV app
     * first (often a non-removable system app on certified Google TV
     * devices), a real, possibly-irreversible device change judged not
     * worth making just to test a fix this project doesn't expect to work.
     * "Supported only with Bridge installed" is a support-boundary decision
     * the user made deliberately aware of this, not a claim that Bridge is
     * confirmed to resolve it - see README's Limitations section.
     */
    static BooleanSupplier prepareTizenTubeCobalt(Context context, String rawTitle) {
        Uri uri = buildYouTubeSearchUri(rawTitle);
        Intent intent = new Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH", uri)
                .setPackage(TIZENTUBE_COBALT_PACKAGE);
        return () -> openWithFallback(context, intent, TIZENTUBE_COBALT_LABEL, false);
    }

    /**
     * Bundles the confirm-overlay label together with the already-decided
     * launch supplier for the YouTube redirect toggle - see
     * prepareYouTubeRedirect(). The two are always computed together from
     * the same Preferences.getYouTubeRedirectTarget() read, so they can
     * never disagree - see TvRelayAccessibilityService.handleYouTubeClick().
     */
    static final class YouTubeRedirect {
        final String label;
        final BooleanSupplier launch;

        private YouTubeRedirect(String label, BooleanSupplier launch) {
            this.label = label;
            this.launch = launch;
        }
    }

    /**
     * Picks which sideloaded YouTube client to redirect to and returns both
     * the matching confirm-overlay label and the already-built launch
     * supplier for that choice, computed together (see YouTubeRedirect's
     * javadoc).
     *
     * The target is an explicit Settings choice (Preferences.
     * getYouTubeRedirectTarget()), not auto-detected install-state guessing -
     * see that method's javadoc for why (a user with both apps installed
     * had no way to actually pick TizenTube Cobalt under the old
     * auto-detection design). If the chosen app isn't actually installed,
     * this doesn't silently substitute the other one - prepareSmartTube()/
     * prepareTizenTubeCobalt() already report that failure the normal way
     * (openWithFallback() returning false), same as picking an uninstalled
     * PlayerApp does.
     */
    static YouTubeRedirect prepareYouTubeRedirect(Context context, String rawTitle) {
        if (Preferences.getYouTubeRedirectTarget(context) == YouTubeRedirectTarget.TIZENTUBE_COBALT) {
            return new YouTubeRedirect(TIZENTUBE_COBALT_LABEL, prepareTizenTubeCobalt(context, rawTitle));
        }
        return new YouTubeRedirect(SMARTTUBE_LABEL, prepareSmartTube(context, rawTitle));
    }

    /**
     * Picks the movie-or-series half of a template pair - the shape every
     * deep-link URI on PlayerApp comes in, for both the IMDb pair and the
     * TMDB-native pair. Extracted so prepare() and open() don't each write
     * out the same ternary.
     */
    private static String forType(MediaType type, String movieTemplate, String seriesTemplate) {
        return type == MediaType.SERIES ? seriesTemplate : movieTemplate;
    }

    /** IMDb-id-based launch for an already-resolved match - see prepare() for the TMDB-native and title-search fast paths. */
    static boolean open(Context context, TvdbMatch match) {
        PlayerApp app = Preferences.getSelectedApp(context);

        // PLEX/JELLYFIN/WHOLPHIN never reach here - prepare() routes
        // title-search players to prepareTitleSearch() before any IMDb
        // resolution happens - and neither does WAKO, which has no
        // IMDb-based URI at all and is turned away by prepare()'s explicit
        // guard before the resolve call. So this stays a genuine
        // should-be-unreachable assertion: if it throws, something upstream
        // regressed (this used to be an unguarded "else build a Stremio
        // URI", which is exactly the bug this explicit check exists to
        // prevent).
        String template = forType(match.getType(), app.getMovieUriTemplate(), app.getSeriesUriTemplate());
        if (template == null) {
            throw new IllegalStateException(app + " does not support IMDb-based open() - see prepareTitleSearch()");
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(String.format(template, match.getImdbId())));
        return openAcrossPackages(context, intent, app);
    }

    /**
     * Tries every package in app.getPackages(), in order, only allowing a
     * generic (packageless) retry past the last one - harmless for a custom
     * URI scheme only that app's own builds claim. Every deep-link player
     * but Nuvio has exactly one package, making this a single attempt with
     * a generic retry, same as before per-player special-casing existed;
     * Nuvio's two real, differently-packaged builds (see PlayerApp's class
     * doc) get tried in order first. intentTemplate carries the
     * action/data/etc. already built by the caller (prepare()'s TMDB-native
     * path, or open()'s IMDb-based path) - copied per attempt since
     * Intent.setPackage() mutates in place and each attempt needs its own
     * target.
     */
    private static boolean openAcrossPackages(Context context, Intent intentTemplate, PlayerApp app) {
        List<String> packages = app.getPackages();
        for (int i = 0; i < packages.size(); i++) {
            boolean isLastPackage = i == packages.size() - 1;
            Intent attempt = new Intent(intentTemplate).setPackage(packages.get(i));
            if (openWithFallback(context, attempt, app.getLabel(), isLastPackage)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param allowGenericFallback whether to retry once more with no
     *                             package restriction if the targeted
     *                             launch fails - appropriate for a custom
     *                             URI scheme only one app is likely to
     *                             handle (Nuvio/Stremio), not for a plain
     *                             https/search Intent that could land
     *                             somewhere unhelpful (a browser, a system
     *                             disambiguator) - see prepareTitleSearch().
     * @return whether an Activity was actually started.
     */
    private static boolean openWithFallback(Context context, Intent intent, String appLabel, boolean allowGenericFallback) {
        // FLAG_ACTIVITY_CLEAR_TASK + NEW_TASK: Nuvio and Stremio both
        // declare their Activity as launchMode="singleTask". Without
        // CLEAR_TASK, if the app is already open in the background, Android
        // sometimes just brings its existing task to the front without
        // reprocessing the new deep link - it stays on the last title it
        // had open. CLEAR_TASK forces a clean start of the Activity on
        // every open. NEW_TASK is also required here regardless of caller,
        // since Context.startActivity needs it when the calling context
        // isn't itself an Activity. Harmless for Plex/Jellyfin's search
        // intents too, so applied unconditionally rather than only for the
        // two players that need it.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        try {
            context.startActivity(intent);
            Log.d(TAG, "Opening " + appLabel + ": " + intent);
            stopOcrSessionIfRunning(context);
            return true;
        } catch (Exception e) {
            // intent.getPackage() is null here whenever the target was set
            // via setClassName() instead of setPackage() (see Jellyfin in
            // prepareTitleSearch()) - log the whole intent instead so the
            // target is always visible regardless of which one was used.
            Log.e(TAG, "Could not open " + appLabel + ": " + intent, e);
            if (!allowGenericFallback) {
                return false;
            }
            try {
                Intent fallback = new Intent(intent);
                fallback.setPackage(null);
                context.startActivity(fallback);
                stopOcrSessionIfRunning(context);
                return true;
            } catch (Exception fallbackFailure) {
                Log.e(TAG, "Fallback failed too. Is " + appLabel + " installed?", fallbackFailure);
                return false;
            }
        }
    }

    /**
     * Tears down the OCR screen-capture session (see OcrCaptureManager),
     * if one happens to be running, whenever a launch actually succeeds -
     * this is the one place every real launch path funnels through
     * (openWithFallback() above), so it's the most reliable available
     * signal that the user is leaving the launcher lobby for another app,
     * without needing to widen TvRelayAccessibilityService's event
     * filtering to watch every package (a real battery/perf/privacy cost
     * already ruled out elsewhere in this codebase - see the voice-search
     * detection work in TvRelayAccessibilityService).
     *
     * Routed through OcrCaptureManager.stopActiveSessionAfterLaunch()
     * rather than calling Context.stopService() directly here - confirmed
     * real bug the first version of this had (2026-08-25): the OCR session
     * is BOTH started AND bound (OcrCaptureManager holds a live
     * ServiceConnection - see its class doc), and a service that's still
     * bound does not actually get destroyed by stopService() alone, even
     * though the call itself doesn't throw or report failure - the
     * recording indicator stayed visible with the service and its
     * MediaProjection still fully alive, confirmed via dumpsys. Only
     * OcrCaptureManager's own instance can properly unbind AND stop it
     * together (see stopSessionIfActive()) - PlayerLauncher, a static
     * utility with no reference to that instance, reaches it via the same
     * kind of static bridge already used for OcrConsentActivity's result
     * callback.
     *
     * The real cost of this: the next OCR trigger after returning to the
     * lobby needs a fresh screen-capture consent grant, since Android's
     * MediaProjection consent is single-use per session (see
     * OcrCaptureManager's class doc) - accepted as the correct trade-off
     * for not leaving the system's persistent recording indicator showing
     * while the user is off watching something in a different app.
     *
     * Exception: in Fire TV mode (Preferences.isFireTvModeEnabled) this is a
     * no-op. There the capture session is deliberately long-lived - it's
     * what keeps FireTvWatcherService's process at foreground priority while
     * it polls, and re-requesting consent on every single card selection
     * would be far worse than leaving the recording indicator up (the
     * reference app leaves it up the whole time Fire TV mode is active too).
     * The session is torn down instead when the user turns Fire TV mode off
     * (FireTvWatcherService.onDestroy -> OcrCaptureManager.shutdown()).
     */
    private static void stopOcrSessionIfRunning(Context context) {
        if (Preferences.isFireTvModeEnabled(context)) {
            return;
        }
        OcrCaptureManager.stopActiveSessionAfterLaunch();
    }
}
