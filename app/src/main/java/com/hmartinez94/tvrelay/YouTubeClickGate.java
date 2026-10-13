package com.hmartinez94.tvrelay;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.util.Log;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a YouTube-looking launcher click really was a YouTube
 * video, by watching which app the click opens.
 *
 * Why: the click payload alone can't tell them apart. A Play Store app
 * suggestion in the Apps tab (confirmed on the ONN 2026-10-13: "Tubi: Free
 * Movies & Live TV") arrives as the exact same android.view.View / bare
 * contentDesc / empty text shape as a real YouTube video card, so
 * TvRelayAccessibilityService.isYouTubeCard() can only produce candidates.
 * What differs is the launcher's own reaction: a YouTube video card starts
 * the official YouTube app (YOUTUBE_TV_PACKAGE), roughly 100 ms BEFORE our
 * click event arrives; an app suggestion starts the Play Store and an app
 * tile starts that app. So a candidate is only confirmed once a window-state
 * event from the YouTube app shows up close to the click, in either order.
 * The service only subscribes to the YouTube package for this - no other
 * app's events are needed, since "no YouTube window" already means "not a
 * YouTube video".
 *
 * Main thread only (accessibility events and the handler's callbacks both
 * run there), so no synchronization.
 */
final class YouTubeClickGate {

    private static final String TAG = "YouTubeClickGate";

    static final String YOUTUBE_TV_PACKAGE = "com.google.android.youtube.tv";

    /** The YouTube window can land just before the click event - accept one this recent. */
    private static final long WINDOW_BEFORE_CLICK_MS = 800;
    /** How long a candidate waits for the YouTube window after the click. */
    private static final long CONFIRM_TIMEOUT_MS = 1500;
    /** How long the installed-app label list is reused before re-querying. */
    private static final long APP_LABEL_CACHE_MS = 60_000;

    interface Listener {
        void onConfirmed(String title);
    }

    private final Context context;
    private final Handler handler;
    private final Listener listener;

    private long lastYouTubeWindowAtMillis;
    private String pendingTitle;

    private Set<String> appLabels;
    private long appLabelsLoadedAtMillis;

    private final Runnable timeout = () -> {
        Log.d(TAG, "YouTube-looking click not confirmed (no YouTube window) - ignored: " + pendingTitle);
        pendingTitle = null;
    };

    YouTubeClickGate(Context context, Handler handler, Listener listener) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.listener = listener;
    }

    /** A window-state event from YOUTUBE_TV_PACKAGE arrived. */
    void onYouTubeWindow() {
        lastYouTubeWindowAtMillis = System.currentTimeMillis();
        if (pendingTitle != null) {
            String title = pendingTitle;
            pendingTitle = null;
            handler.removeCallbacks(timeout);
            Log.d(TAG, "Confirmed by YouTube window opening after the click: " + title);
            listener.onConfirmed(title);
        }
    }

    /** A click isYouTubeCard() accepted. Confirms now, later, or never. */
    void onCandidateClick(String title) {
        handler.removeCallbacks(timeout);
        pendingTitle = null;

        // An Apps-tab "YouTube" tile opens the YouTube app too, so the
        // window check alone would pass it - an installed app's exact name
        // is never a video we want to search for.
        if (isInstalledAppLabel(title)) {
            Log.d(TAG, "Click title is an installed app's name - ignored: " + title);
            return;
        }
        // Nothing to wait for without the official app (e.g. removed in
        // favor of a replacement): keep the old launch-immediately behavior.
        if (!isYouTubeAppInstalled()) {
            Log.d(TAG, "Official YouTube app not installed - skipping confirmation: " + title);
            listener.onConfirmed(title);
            return;
        }
        if (System.currentTimeMillis() - lastYouTubeWindowAtMillis < WINDOW_BEFORE_CLICK_MS) {
            Log.d(TAG, "Confirmed by YouTube window opening just before the click: " + title);
            listener.onConfirmed(title);
            return;
        }
        pendingTitle = title;
        handler.postDelayed(timeout, CONFIRM_TIMEOUT_MS);
    }

    void shutdown() {
        handler.removeCallbacks(timeout);
        pendingTitle = null;
    }

    private boolean isYouTubeAppInstalled() {
        try {
            context.getPackageManager().getPackageInfo(YOUTUBE_TV_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private boolean isInstalledAppLabel(String title) {
        long now = System.currentTimeMillis();
        if (appLabels == null || now - appLabelsLoadedAtMillis > APP_LABEL_CACHE_MS) {
            appLabels = loadAppLabels();
            appLabelsLoadedAtMillis = now;
        }
        return appLabels.contains(title.trim().toLowerCase(Locale.ROOT));
    }

    /** Labels of every launcher-visible app (TV and phone launchers), lowercased. */
    private Set<String> loadAppLabels() {
        Set<String> labels = new HashSet<>();
        PackageManager pm = context.getPackageManager();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            List<ResolveInfo> apps = pm.queryIntentActivities(query, 0);
            for (ResolveInfo app : apps) {
                CharSequence label = app.loadLabel(pm);
                if (label != null) {
                    labels.add(label.toString().trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return labels;
    }
}
