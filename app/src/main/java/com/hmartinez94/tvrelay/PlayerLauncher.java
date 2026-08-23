package com.hmartinez94.tvrelay;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

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
 *
 * Takes a plain Context (not specifically AccessibilityService) so it can be
 * called both from the accessibility service and from a regular Activity
 * (the manual search fallback - see SearchStepFragment).
 */
final class PlayerLauncher {

    private static final String TAG = "PlayerLauncher";

    private PlayerLauncher() {
    }

    /**
     * Resolves (if needed) and opens a candidate in one step. For Nuvio with
     * a TMDB-provenance candidate, this needs no network call at all - see
     * prepare(). Otherwise this is a blocking network call for TMDB (the
     * external_ids fetch) - call only from a background thread. Returns
     * whether it succeeded (false if resolution failed).
     */
    static boolean openCandidate(Context context, TitleCandidate candidate) {
        Runnable launch = prepare(context, candidate);
        if (launch == null) {
            return false;
        }
        launch.run();
        return true;
    }

    /**
     * Returns a Runnable that opens this candidate, or null if it couldn't
     * be resolved - without actually opening anything yet. Exists so a
     * caller can decide to open it later (e.g. only once a confirm button
     * is tapped - see TvRelayAccessibilityService) while still doing any
     * necessary network resolution up front, on the same background thread
     * it already had to be on.
     *
     * For Nuvio + a TMDB-provenance candidate, nothing needs resolving:
     * Nuvio accepts a TMDB id directly (nuvio://tmdb/{movie|tv}/{id}), so
     * the returned Runnable is a plain, already-built Intent launch with no
     * network call in it at all - faster, and it works even for a
     * candidate TMDB hasn't cross-referenced to an IMDb id yet (see
     * TmdbClient.resolveImdbId, which would otherwise fail those). Every
     * other case (TheTVDB candidates, or Stremio - which has no TMDB-native
     * scheme) resolves an IMDb id first, exactly as before this existed -
     * a blocking network call for TMDB in that case.
     */
    static Runnable prepare(Context context, TitleCandidate candidate) {
        PlayerApp app = Preferences.getSelectedApp(context);
        if (app == PlayerApp.NUVIO && candidate.tmdbMediaPath != null) {
            Uri uri = Uri.parse("nuvio://tmdb/" + candidate.tmdbMediaPath + "/" + candidate.tmdbId);
            return () -> openWithFallback(context, uri, app.getPackageName(), app.getLabel());
        }
        TvdbMatch match = MetadataResolver.resolve(context, candidate);
        return match != null ? () -> open(context, match) : null;
    }

    /** IMDb-id-based launch for an already-resolved match - see prepare() for the TMDB-native fast path. */
    static void open(Context context, TvdbMatch match) {
        PlayerApp app = Preferences.getSelectedApp(context);

        Uri uri;
        if (app == PlayerApp.NUVIO) {
            uri = match.getType() == MediaType.MOVIE
                    ? Uri.parse("nuvio://movie/" + match.getImdbId())
                    : Uri.parse("nuvio://detail/tv/" + match.getImdbId());
        } else {
            String stremioType = match.getType() == MediaType.SERIES ? "series" : "movie";
            uri = Uri.parse("stremio:///detail/" + stremioType + "/" + match.getImdbId());
        }

        openWithFallback(context, uri, app.getPackageName(), app.getLabel());
    }

    private static void openWithFallback(Context context, Uri uri, String targetPackage, String appLabel) {
        // FLAG_ACTIVITY_CLEAR_TASK + NEW_TASK: both Nuvio and Stremio declare
        // their Activity as launchMode="singleTask". Without CLEAR_TASK, if
        // the app is already open in the background, Android sometimes just
        // brings its existing task to the front without reprocessing the new
        // deep link - it stays on the last title it had open. CLEAR_TASK
        // forces a clean start of the Activity on every open. NEW_TASK is
        // also required here regardless of caller, since Context.startActivity
        // needs it when the calling context isn't itself an Activity.
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(uri);
        intent.setPackage(targetPackage);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        try {
            context.startActivity(intent);
            Log.d(TAG, "Opening " + appLabel + ": " + uri);
        } catch (Exception e) {
            Log.e(TAG, "Could not open " + appLabel + " via " + targetPackage + ", retrying without package", e);
            try {
                Intent fallback = new Intent(Intent.ACTION_VIEW);
                fallback.setData(uri);
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                context.startActivity(fallback);
            } catch (Exception fallbackFailure) {
                Log.e(TAG, "Fallback failed too. Is " + appLabel + " installed?", fallbackFailure);
            }
        }
    }
}
