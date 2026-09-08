package com.hmartinez94.tvrelay;

import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fire TV detection, the way the accessibility-click pipeline can't do it.
 *
 * On the current Fire TV home screen (com.amazon.tv.launcher's
 * HomeActivity_vNext, the 2026 redesign) a sideloaded accessibility service
 * receives no AccessibilityEvents at all - not even window-state changes -
 * so the click path the Google TV side relies on is simply dead here (see
 * CLAUDE.md's "Fire TV wall", re-confirmed 2026-09-07). This service takes
 * the other route the reference app (Yushetf33/TvReccomendationBridge)
 * documents and that was confirmed working on a real Fire TV Stick the same
 * day:
 *
 *  1. Poll UsageStats (Usage Access grant) for the foreground activity.
 *     Selecting a recommendation on the Fire TV home opens the launcher's
 *     OWN in-app detail page - DETAILS_PAGE_CLASS - a clean, reliable "the
 *     user just opened a recommendation" signal that needs no accessibility
 *     event, confirmed live via `dumpsys usagestats` (launcher versionName
 *     7300121.1). That detail page shows the full title in large, clear
 *     text - see the "9 to 5" capture referenced in the plan.
 *  2. On that transition, read the title off the screen with the existing
 *     MediaProjection + on-device OCR pipeline (OcrCaptureManager), then
 *     feed it into the exact same resolve -> confirm-overlay -> launch
 *     pipeline every other trigger uses (TitleHandler).
 *
 * Deliberately NOT an accessibility service and NOT dependent on one: Fire
 * OS's Accessibility settings page is frequently blank/unresponsive for
 * sideloaded apps (the reason launcher-replacement apps need
 * WRITE_SECURE_SETTINGS via ADB), so Fire TV mode avoids it entirely -
 * usage access + screen recording are both grantable from normal system
 * screens, no ADB.
 *
 * Lifecycle: a plain started Service (not itself foreground). It relies for
 * process priority on OcrCaptureForegroundService (foreground,
 * mediaProjection), which ensureSession() brings up at activation and which
 * is deliberately kept alive across launches in Fire TV mode (see
 * PlayerLauncher.stopOcrSessionIfRunning()). START_NOT_STICKY: a reboot or
 * process death clears the screen-recording consent anyway, so there's
 * nothing to auto-restart to - the user re-activates from Settings, matching
 * the reference app's documented behavior.
 */
public final class FireTvWatcherService extends Service {

    private static final String TAG = "FireTvWatcher";

    /**
     * The Fire TV launcher's own in-app detail page, opened when a
     * recommendation is selected. Confirmed via live `dumpsys usagestats`
     * on a real Fire TV Stick 2026-09-07 (launcher versionName 7300121.1):
     * selecting an Amazon-catalog card produces an ACTIVITY_RESUMED for this
     * exact class, distinct from HomeActivity_vNext. A card that instead
     * deep-links straight into a third-party app (e.g. Netflix) bypasses
     * this page and won't be caught - but Amazon's own rows dominate the
     * Fire TV home. If Amazon renames this activity in a future launcher
     * build, this constant is the thing to re-confirm the same way.
     */
    private static final String DETAILS_PAGE_CLASS =
            "com.amazon.tv.launcher.deeplink.activity.DetailsPageDeepLinkActivityDI";

    /**
     * The Fire TV launcher's actual home/lobby screen. Confirmed via live
     * `dumpsys activity activities` (mResumedActivity) on a real Fire TV
     * Stick 2026-09-08. Used to abandon a pending overlay match once the
     * user is genuinely back home - see checkForegroundForDetailPage()'s
     * lobby-return check, added the same day to fix a confirmed real bug:
     * see that method's javadoc.
     */
    private static final String HOME_CLASS = "com.amazon.tv.launcher.ui.HomeActivity_vNext";

    private static final long POLL_INTERVAL_MS = 1500;
    // Lookback window each poll queries. Comfortably wider than the interval
    // so a UsageStats event that lands a second or two late is never missed
    // between ticks; timestamp-based dedup (lastHandledEventTime) stops the
    // overlap from re-triggering.
    private static final long POLL_WINDOW_MS = 8000;
    // Minimum spacing between two OCR triggers, mirroring the accessibility
    // service's OCR_DEBOUNCE_MS - a title has no debounce of its own yet at
    // trigger time (TitleHandler dedups by title only after OCR resolves).
    private static final long OCR_DEBOUNCE_MS = 3000;

    static final String ACTION_STOP = "com.hmartinez94.tvrelay.action.STOP_FIRE_TV_WATCH";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();

    private UsageStatsManager usageStatsManager;
    private WatchNowOverlay overlay;
    private OcrCaptureManager ocrCaptureManager;
    private TitleHandler titleHandler;

    // Dedup/rate-limit state for the poll loop (main thread only).
    private long lastHandledEventTime;
    private long lastTriggerAtMillis;
    private boolean polling;

    private final Runnable pollTick = this::pollTick;

    /** Starts Fire TV mode watching - call from a foreground context (the Settings activation screen). */
    static void start(Context context) {
        context.startService(new Intent(context, FireTvWatcherService.class));
    }

    /** Stops Fire TV mode watching. */
    static void stop(Context context) {
        Intent intent = new Intent(context, FireTvWatcherService.class);
        intent.setAction(ACTION_STOP);
        // startService with the STOP action (rather than stopService
        // directly) so onStartCommand can tear down its own state cleanly
        // even if the service wasn't already running.
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        usageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        overlay = new WatchNowOverlay(this);
        ocrCaptureManager = new OcrCaptureManager(this, overlay);
        // No-op coordinator: unlike the accessibility service, the watcher
        // has no window-state event filtering to keep in sync and no
        // voice-search cooldown to record - see TitleHandler.Coordinator.
        titleHandler = new TitleHandler(this, overlay, backgroundExecutor, mainHandler,
                new TitleHandler.Coordinator() {
                    @Override
                    public void onOverlayStateChanged() {
                    }

                    @Override
                    public void onOverlayActivity() {
                    }
                });
        Log.d(TAG, "Fire TV watcher created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Log.d(TAG, "Stop requested");
            stopSelf();
            return START_NOT_STICKY;
        }
        // Prime the MediaProjection session now (shows the one-time
        // screen-capture consent) so the process gains a foreground service
        // and the first real detail-page capture is warm. If consent is
        // denied, the FailureReason.CONSENT_DENIED path below stops the
        // service rather than re-prompting on every card.
        if (usageStatsManager != null && ocrCaptureManager != null) {
            ocrCaptureManager.ensureSession();
        }
        startPolling();
        return START_NOT_STICKY;
    }

    private void startPolling() {
        if (polling) {
            return;
        }
        polling = true;
        Log.d(TAG, "Fire TV watcher polling started");
        mainHandler.post(pollTick);
    }

    private void pollTick() {
        if (!polling) {
            return;
        }
        try {
            checkForegroundForDetailPage();
        } catch (Exception e) {
            Log.e(TAG, "Poll tick failed", e);
        }
        mainHandler.postDelayed(pollTick, POLL_INTERVAL_MS);
    }

    /**
     * Reads recent UsageStats foreground events, fires an OCR capture on a
     * fresh transition into the launcher's detail page, and abandons any
     * pending overlay match once the user is genuinely back at the Fire TV
     * home screen. Returns silently (no trigger) when Usage Access isn't
     * granted - queryEvents simply yields nothing - so this fails safe.
     *
     * The lobby-return half mirrors TvRelayAccessibilityService's
     * handleLauncherLobbyReturn() for Google TV, sourced from this service's
     * own UsageStats polling instead of a window-state accessibility event
     * (Fire TV never delivers those - see the class doc). Confirmed real bug
     * this fixes (2026-09-08, live on the user's Fire Stick): with no such
     * check, a "Watch now"/"Choose in {App}" match left unconfirmed just kept
     * hiding and reappearing on its own reappear cycle (see WatchNowOverlay)
     * for up to its 5-minute lifetime cap, floating over the actual Fire TV
     * home screen the whole time after the user backed out - confirmed via
     * `dumpsys activity activities` (mResumedActivity=HomeActivity_vNext)
     * with a live TVRelay SYSTEM_ALERT_WINDOW still on top, plus a
     * screenshot. Checked unconditionally, every tick, independent of the
     * OCR-trigger dedup below: notifyLauncherLobby()/abandon() are no-ops
     * once nothing is pending, so there's no cost to re-checking.
     */
    private void checkForegroundForDetailPage() {
        if (usageStatsManager == null) {
            return;
        }
        long now = System.currentTimeMillis();
        UsageEvents events = usageStatsManager.queryEvents(now - POLL_WINDOW_MS, now);
        if (events == null) {
            return;
        }
        UsageEvents.Event event = new UsageEvents.Event();
        String latestClass = null;
        long latestTime = -1;
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (event.getEventType() != UsageEvents.Event.MOVE_TO_FOREGROUND) {
                continue;
            }
            if (event.getTimeStamp() >= latestTime) {
                latestTime = event.getTimeStamp();
                latestClass = event.getClassName();
            }
        }

        if (HOME_CLASS.equals(latestClass) && overlay != null && overlay.hasPendingMatch()) {
            Log.d(TAG, "Back at Fire TV home - abandoning pending match");
            overlay.notifyLauncherLobby();
        }

        if (latestClass == null || latestTime <= lastHandledEventTime) {
            return;
        }
        if (!DETAILS_PAGE_CLASS.equals(latestClass)) {
            // The user is on some other screen now (home, an app, etc.) -
            // advance the dedup marker so we don't re-evaluate this same
            // event again, but don't trigger.
            lastHandledEventTime = latestTime;
            return;
        }
        lastHandledEventTime = latestTime;
        if ((now - lastTriggerAtMillis) < OCR_DEBOUNCE_MS) {
            return;
        }
        lastTriggerAtMillis = now;
        Log.d(TAG, "Detail page entered - triggering OCR capture");
        triggerCapture();
    }

    private void triggerCapture() {
        boolean confirmFirst = overlay != null && overlay.isPermissionGranted();
        if (confirmFirst) {
            overlay.showLoading();
        }
        ocrCaptureManager.requestTitleCapture(new OcrCaptureManager.Callback() {
            @Override
            public void onTitleExtracted(String title) {
                Log.d(TAG, "OCR-detected title (Fire TV): " + title);
                titleHandler.handle(title);
            }

            @Override
            public void onFailure(OcrCaptureManager.FailureReason reason) {
                Log.w(TAG, "Fire TV OCR capture failed: " + reason);
                if (confirmFirst && overlay != null) {
                    overlay.abandon();
                }
                if (reason == OcrCaptureManager.FailureReason.CONSENT_DENIED) {
                    // The user declined screen recording - stop rather than
                    // re-prompting on every card selection. Fire TV mode
                    // stays "on" in Preferences but isn't running; the user
                    // re-activates from Settings when ready.
                    Log.d(TAG, "Screen-capture consent denied - stopping Fire TV watcher");
                    stopSelf();
                }
            }
        });
    }

    @Override
    public void onDestroy() {
        polling = false;
        mainHandler.removeCallbacks(pollTick);
        if (titleHandler != null) {
            titleHandler.shutdown();
        }
        if (overlay != null) {
            overlay.abandon();
        }
        if (ocrCaptureManager != null) {
            ocrCaptureManager.shutdown();
        }
        backgroundExecutor.shutdown();
        Log.d(TAG, "Fire TV watcher destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
