package com.hmartinez94.tvrelay;

import java.io.Serializable;

/**
 * A single search result shown to the user when a title is ambiguous - see
 * MatchTrayView / MatchChooserStepFragment. Not necessarily launchable yet:
 * TMDB candidates carry a null imdbId until MetadataResolver.resolve() is
 * called on the one the user picks, since fetching it for every candidate
 * upfront (an extra network call each on TMDB - see TmdbClient) would be
 * wasted work for the five or so the user never chooses. TheTVDB candidates
 * already have it - TheTVDB's search response includes remote_ids for
 * every result, so there's nothing left to resolve.
 *
 * Serializable rather than Parcelable: there are at most a handful of these
 * per lookup, they only ever travel in an Intent/Bundle extra between this
 * app's own components, and Serializable is one line instead of the
 * ~30 Parcelable normally needs.
 */
final class TitleCandidate implements Serializable {

    final String displayTitle;
    final String akaTitle; // non-null only when meaningfully different from displayTitle - see TmdbClient
    final int year; // Integer.MIN_VALUE when the provider didn't give one
    final MediaType type;
    final boolean isExactMatch;
    final String imdbId; // null until resolved, for TMDB candidates
    final int tmdbId; // 0 for TheTVDB candidates
    final String tmdbMediaPath; // "movie" or "tv" - TMDB only, null otherwise
    final String jellyfinItemId; // non-null only for a hit from the user's own Jellyfin library - see JellyfinClient

    private TitleCandidate(String displayTitle, String akaTitle, int year, MediaType type, boolean isExactMatch,
                            String imdbId, int tmdbId, String tmdbMediaPath, String jellyfinItemId) {
        this.displayTitle = displayTitle;
        this.akaTitle = akaTitle;
        this.year = year;
        this.type = type;
        this.isExactMatch = isExactMatch;
        this.imdbId = imdbId;
        this.tmdbId = tmdbId;
        this.tmdbMediaPath = tmdbMediaPath;
        this.jellyfinItemId = jellyfinItemId;
    }

    static TitleCandidate fromTvdb(String displayTitle, int year, MediaType type,
                                    boolean isExactMatch, String imdbId) {
        return new TitleCandidate(displayTitle, null, year, type, isExactMatch, imdbId, 0, null, null);
    }

    static TitleCandidate fromTmdb(String displayTitle, String akaTitle, int year, MediaType type,
                                    boolean isExactMatch, int tmdbId, String tmdbMediaPath) {
        return new TitleCandidate(displayTitle, akaTitle, year, type, isExactMatch, null, tmdbId, tmdbMediaPath, null);
    }

    /**
     * A hit from the user's own Jellyfin library (JellyfinClient) - already
     * directly launchable via its item id (see PlayerLauncher.prepare()),
     * unlike a TMDB candidate, which still needs an IMDb id resolved. Not
     * "resolved" in isResolved()'s sense (that's specifically about
     * imdbId) - jellyfinItemId is a parallel, separate way a candidate can
     * already be launchable.
     */
    static TitleCandidate fromJellyfin(String displayTitle, int year, MediaType type,
                                        boolean isExactMatch, String itemId) {
        return new TitleCandidate(displayTitle, null, year, type, isExactMatch, null, 0, null, itemId);
    }

    boolean isResolved() {
        return imdbId != null;
    }
}
