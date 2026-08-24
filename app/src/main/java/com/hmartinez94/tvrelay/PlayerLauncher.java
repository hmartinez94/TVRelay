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
 * Plex / Jellyfin: neither has a usable content deep link (see PlayerApp's
 * class doc and CLAUDE.md) - both get a plain title search hand-off
 * instead, via prepareTitleSearch(). Unlike the three players above, this
 * never touches MetadataResolver at all - see TvRelayAccessibilityService
 * and SearchStepFragment, which branch on PlayerApp.usesTitleSearch()
 * before ever calling resolveCandidates().
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
     * content deep link, so this just opens the player's own search screen
     * with the clicked/typed title pre-filled. No network call, so unlike
     * the IMDb/TMDB paths this can't fail to "resolve" - the only way the
     * returned supplier can report false is if the target app itself
     * couldn't be launched (e.g. not installed).
     */
    static BooleanSupplier prepareTitleSearch(Context context, String rawTitle) {
        PlayerApp app = Preferences.getSelectedApp(context);
        String title = TitleCleanup.stripTrailingParentheticals(rawTitle);

        Intent intent;
        switch (app) {
            case PLEX:
                // Only /movie/{slug} is confirmed by Plex staff to work
                // (see CLAUDE.md); /search?q= is unverified - probe it
                // on-device (adb am start) before shipping. No token-based
                // slug lookup - deliberately skipped, see CLAUDE.md.
                Uri searchUri = Uri.parse("https://watch.plex.tv/search?q=" + Uri.encode(title));
                intent = new Intent(Intent.ACTION_VIEW, searchUri);
                intent.setPackage(app.getPackageName());
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
     * SmartTube redirect for a YouTube video recommendation card - entirely
     * separate from the PlayerApp movie/show player choice (a user can have
     * Nuvio selected for movies and still get YouTube videos redirected to
     * SmartTube). See Preferences.isSmartTubeEnabled() and
     * TvRelayAccessibilityService's YouTube-marker detection, which decides
     * when this gets called at all - this method itself doesn't check the
     * setting. Opens a YouTube search for the title (no direct video-id
     * deep link attempted - see CLAUDE.md), trying SmartTube's stable
     * package first, then its beta package if that fails. No packageless
     * fallback for either attempt, same reasoning as prepareTitleSearch():
     * a bare YouTube search URL would otherwise silently open in a browser
     * or the official YouTube app instead of SmartTube, which defeats the
     * point of this feature.
     */
    static BooleanSupplier prepareSmartTube(Context context, String rawTitle) {
        String title = TitleCleanup.stripTrailingParentheticals(rawTitle);
        Uri uri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(title));
        return () -> {
            Intent stable = new Intent(Intent.ACTION_VIEW, uri).setPackage(SMARTTUBE_STABLE_PACKAGE);
            if (openWithFallback(context, stable, SMARTTUBE_LABEL, false)) {
                return true;
            }
            Intent beta = new Intent(Intent.ACTION_VIEW, uri).setPackage(SMARTTUBE_BETA_PACKAGE);
            return openWithFallback(context, beta, SMARTTUBE_LABEL, false);
        };
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
                return true;
            } catch (Exception fallbackFailure) {
                Log.e(TAG, "Fallback failed too. Is " + appLabel + " installed?", fallbackFailure);
                return false;
            }
        }
    }
}
