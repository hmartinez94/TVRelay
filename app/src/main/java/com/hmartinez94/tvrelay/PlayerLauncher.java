package com.hmartinez94.tvrelay;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

/**
 * Opens a resolved title in whichever app the user picked in Settings.
 *
 * Nuvio: nuvio://movie/{imdbId} (movies), nuvio://detail/tv/{imdbId} (series).
 * Stremio: stremio:///detail/movie/{imdbId}, stremio:///detail/series/{imdbId}.
 *
 * Takes a plain Context (not specifically AccessibilityService) so it can be
 * called both from the accessibility service and from a regular Activity
 * (the manual search fallback - see SearchStepFragment).
 */
final class PlayerLauncher {

    private static final String TAG = "PlayerLauncher";

    private PlayerLauncher() {
    }

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
