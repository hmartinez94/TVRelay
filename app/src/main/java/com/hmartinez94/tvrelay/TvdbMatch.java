package com.hmartinez94.tvrelay;

/** A title resolved via TheTVDB: its IMDB id and whether it's a movie or series. */
public final class TvdbMatch {

    private final String imdbId;
    private final MediaType type;

    public TvdbMatch(String imdbId, MediaType type) {
        this.imdbId = imdbId;
        this.type = type;
    }

    public String getImdbId() {
        return imdbId;
    }

    public MediaType getType() {
        return type;
    }

    @Override
    public String toString() {
        return imdbId + " (" + type + ")";
    }
}
