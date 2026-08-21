package com.hmartinez94.tvrelay;

import android.content.Context;

/** Resolves a title to an IMDB id via whichever provider is chosen in Settings. */
final class MetadataResolver {

    private MetadataResolver() {
    }

    /** Blocking network call(s) - call only from a background thread. */
    static TvdbMatch findImdbId(Context context, String title) {
        if (Preferences.getMetadataProvider(context) == MetadataProvider.TMDB) {
            return TmdbClient.findImdbId(context, title);
        }
        return TvdbClient.findImdbId(title);
    }
}
