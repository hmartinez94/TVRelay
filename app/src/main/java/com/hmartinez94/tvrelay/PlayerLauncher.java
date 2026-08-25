package com.hmartinez94.tvrelay;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import java.util.function.BooleanSupplier;

/**
 * Opens a title in whichever app the user picked in Settings.
 *
 * Nuvio: nuvio://movie/{imdbId} (movies), nuvio://detail/tv/{imdbId} (series) -
 * OR, when the candidate came from TMDB, nuvio://tmdb/{movie|tv}/{tmdbId}
 * directly (see prepare()/openCandidate() - confirmed working on-device
 * 2026-08-23, verified against com.nuvio.app the same way the IMDb scheme
 * already was, via `adb shell am start`).
 * Stremio: stremio:///detail/movie/{imdbId}, stremio:///detail/series/{imdbId} -
 * always IMDb-based, no TMDB-native equivalent exists in Stremio's addon
 * ecosystem, which is built around IMDb ids universally.
 * WuPlay: wuplay://movie/{imdbId}, wuplay://series/{imdbId} - IMDb-based,
 * confirmed working on-device 2026-08-24. Added in WuPlay's own
 * v0.8.3-beta release the same day - CLAUDE.md's "WuPlay wall" originally
 * (2026-08-23) found no content deep link existed at all, only a
 * profile-switcher; that finding is now reversed, not still true.
 * Plex / Jellyfin: neither has a usable content deep link (see PlayerApp's
 * class doc and CLAUDE.md) - both get a plain title search hand-off
 * instead, via prepareTitleSearch(). Jellyfin pre-fills the query;
 * Plex's ACTION_SEARCH route (the only working one - see CLAUDE.md for a
 * decompiled trace) opens Plex's real search screen but does NOT pre-fill
 * it, a confirmed gap in Plex's own app, not something fixable here. (Plex
 * is currently disabled - not removed - see PlayerApp.isEnabled().) Unlike
 * the deep-link players above, title search never touches MetadataResolver
 * at all - see TvRelayAccessibilityService and SearchStepFragment, which
 * branch on PlayerApp.usesTitleSearch() before ever calling
 * resolveCandidates().
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
     * Opens a plain title search directly - the manual-search entry point
     * for title-search players (Plex, Jellyfin), which skip
     * MetadataResolver entirely. See prepareTitleSearch().
     */
    static boolean openTitleSearch(Context context, String rawTitle) {
        return prepareTitleSearch(context, rawTitle).getAsBoolean();
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
     * prepareTitleSearch(). For Nuvio + a TMDB-provenance candidate,
     * likewise nothing needs resolving: Nuvio accepts a TMDB id directly
     * (nuvio://tmdb/{movie|tv}/{id}), so the returned supplier is a plain,
     * already-built Intent launch with no network call in it at all -
     * faster, and it works even for a candidate TMDB hasn't cross-referenced
     * to an IMDb id yet (see TmdbClient.resolveImdbId, which would
     * otherwise fail those). Every other case (TheTVDB candidates, or
     * Stremio - which has no TMDB-native scheme) resolves an IMDb id first,
     * exactly as before this existed - a blocking network call for TMDB in
     * that case.
     */
    static BooleanSupplier prepare(Context context, TitleCandidate candidate) {
        PlayerApp app = Preferences.getSelectedApp(context);
        if (app.usesTitleSearch()) {
            return prepareTitleSearch(context, candidate.displayTitle);
        }
        if (app == PlayerApp.NUVIO && candidate.tmdbMediaPath != null) {
            Uri uri = Uri.parse("nuvio://tmdb/" + candidate.tmdbMediaPath + "/" + candidate.tmdbId);
            Intent intent = new Intent(Intent.ACTION_VIEW, uri).setPackage(app.getPackageName());
            return () -> openWithFallback(context, intent, app.getLabel(), true);
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

        Intent intent;
        switch (app) {
            case PLEX:
                // https://watch.plex.tv/search?q= is dead - confirmed on
                // real hardware (ActivityNotFoundException; no /search path
                // is registered at all, see CLAUDE.md). This ACTION_SEARCH
                // route is real and confirmed working (SplashActivity does
                // have a matching, if uncategorized, intent-filter - hence
                // the explicit component below) but does NOT pre-fill the
                // query: traced via decompilation all the way through
                // Plex's own internal SearchActivity -> ... -> c2() relay,
                // which correctly forwards "query" right up until the final
                // hand-off to MobileSearchActivity, which doesn't consume
                // it - a real bug in Plex's own app on this build, not
                // something wrong on our end. Still sent here (harmless,
                // and free if Plex ever fixes it) because this at least
                // reliably opens Plex's real search screen instead of
                // nothing at all - see CLAUDE.md's "Plex removed" section
                // for the full decompiled trace.
                intent = new Intent(Intent.ACTION_SEARCH);
                intent.putExtra(SearchManager.QUERY, title);
                intent.setClassName(app.getPackageName(), "com.plexapp.plex.activities.SplashActivity");
                break;
            case JELLYFIN:
                // StartupActivity declares an ACTION_SEARCH filter and
                // really does read SearchManager.QUERY - verified two ways:
                // directly against Jellyfin's own manifest/source, and by
                // cross-checking Bananz0/OpenTVBridge's independent
                // implementation (2026-08-24, see CLAUDE.md), which targets
                // this exact class the same way. The alias
                // ".startup.StartupActivity" declares no intent-filters of
                // its own, so an explicit component removes any reliance on
                // implicit-intent resolution picking the right one -
                // there's no way for the user to test this locally without
                // a Jellyfin server, so this is the more defensive choice.
                intent = new Intent(Intent.ACTION_SEARCH);
                intent.putExtra(SearchManager.QUERY, title);
                intent.setClassName(app.getPackageName(), "org.jellyfin.androidtv.ui.startup.StartupActivity");
                break;
            default:
                // NUVIO/STREMIO never reach here - prepare() only calls
                // this method when app.usesTitleSearch() is true.
                throw new IllegalStateException("prepareTitleSearch() called for a non-search player: " + app);
        }

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
     * Shared by prepareSmartTube() and prepareTizenTubeCobalt() - both target
     * the exact same YouTube search URL contract
     * (https://www.youtube.com/results?search_query={title}), just a
     * different package. Extracted so the two paths can't drift apart the
     * way CLAUDE.md's TitleSearchFallbacks section describes real bugs
     * arising from duplicating near-identical logic across code paths.
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
     * for the title (no direct video-id deep link attempted - see
     * CLAUDE.md), trying SmartTube's stable package first, then its beta
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

    /** IMDb-id-based launch for an already-resolved match - see prepare() for the TMDB-native and title-search fast paths. */
    static boolean open(Context context, TvdbMatch match) {
        PlayerApp app = Preferences.getSelectedApp(context);

        Intent intent;
        switch (app) {
            case NUVIO:
                Uri nuvioUri = match.getType() == MediaType.MOVIE
                        ? Uri.parse("nuvio://movie/" + match.getImdbId())
                        : Uri.parse("nuvio://detail/tv/" + match.getImdbId());
                intent = new Intent(Intent.ACTION_VIEW, nuvioUri);
                break;
            case STREMIO:
                String stremioType = match.getType() == MediaType.SERIES ? "series" : "movie";
                intent = new Intent(Intent.ACTION_VIEW, Uri.parse("stremio:///detail/" + stremioType + "/" + match.getImdbId()));
                break;
            case WUPLAY:
                // wuplay://{movie|series}/{imdbId} - confirmed working
                // on-device 2026-08-24, added in WuPlay's own v0.8.3-beta
                // release the same day (see PlayerApp's class doc and
                // CLAUDE.md's "WuPlay wall" - this reverses that section's
                // original "no content deep link exists" finding).
                String wuplayType = match.getType() == MediaType.SERIES ? "series" : "movie";
                intent = new Intent(Intent.ACTION_VIEW, Uri.parse("wuplay://" + wuplayType + "/" + match.getImdbId()));
                break;
            default:
                // PLEX/JELLYFIN never reach here - prepare() routes
                // title-search players to prepareTitleSearch() before any
                // IMDb resolution happens. If this throws, something
                // upstream regressed (this used to be an unguarded "else
                // build a Stremio URI", which is exactly the bug this
                // explicit switch exists to prevent).
                throw new IllegalStateException(app + " does not support IMDb-based open() - see prepareTitleSearch()");
        }
        intent.setPackage(app.getPackageName());

        return openWithFallback(context, intent, app.getLabel(), true);
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
            stopOcrSessionIfRunning();
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
                stopOcrSessionIfRunning();
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
     * already ruled out elsewhere in this codebase - see the "voice search
     * wall" in CLAUDE.md).
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
     */
    private static void stopOcrSessionIfRunning() {
        OcrCaptureManager.stopActiveSessionAfterLaunch();
    }
}
