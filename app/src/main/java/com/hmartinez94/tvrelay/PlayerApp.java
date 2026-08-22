package com.hmartinez94.tvrelay;

/** The app a resolved title gets opened in. */
public enum PlayerApp {
    NUVIO("com.nuvio.app", "Nuvio"),
    STREMIO("com.stremio.one", "Stremio");

    private final String packageName;
    private final String label;

    PlayerApp(String packageName, String label) {
        this.packageName = packageName;
        this.label = label;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getLabel() {
        return label;
    }
}
