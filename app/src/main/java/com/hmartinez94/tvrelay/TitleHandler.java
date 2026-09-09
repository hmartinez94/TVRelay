package com.hmartinez94.tvrelay;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;

/**
 * Turns a detected title into a launch, via the shared
 * resolve -> confirm-overlay -> launch pipeline every trigger funnels
 * through. Extracted from TvRelayAccessibilityService (2026-09-07) so the
 * Google TV accessibility path and the new Fire TV UsageStats+OCR path
 * (FireTvWatcherService) run identical logic instead of maintaining two
 * copies.
 *
 * The only thing that differs between the two callers is what needs to
 * happen around an overlay-state change: the accessibility service has to
 * keep TYPE_WINDOW_STATE_CHANGED live-conditional on whether a match is
 * pending (refreshEventTypes()) and record its own overlay transitions for
 * the voice-search cooldown (markOwnOverlayActivity()); the Fire TV watcher
 * needs neither. Those two hooks are the Coordinator; the Fire TV watcher
 * passes no-ops.
 *
 * All public methods must be called on the main thread (like the code this
 * was extracted from) - handle() posts its own background work to the
 * executor and marshals UI back via the main Handler.
 */
final class TitleHandler {

    private static final String TAG = "TitleHandler";

    /** Avoids re-handling the same title repeatedly - identical to the service's old constant. */
    private static final long DEBOUNCE_MS = 4000;

    /**
     * The two overlay-related side effects the accessibility service needs
     * and the Fire TV watcher doesn't - see class doc. Both are called on
     * the main thread.
     */
    interface Coordinator {
        void onOverlayStateChanged();
        void onOverlayActivity();
    }

    private final Context appContext;
    // The host used to build MatchTrayOverlay - NOT the application context.
    // The accessibility service passes itself here so the tray can use the
    // permission-free TYPE_ACCESSIBILITY_OVERLAY; the Fire TV watcher passes
    // its own Service, where that overlay type isn't available and the tray
    // falls back to TYPE_APPLICATION_OVERLAY (see MatchTrayOverlay.show()).
    private final Context trayHostContext;
    private final WatchNowOverlay overlay;
    private final ExecutorService backgroundExecutor;
    private final Handler mainHandler;
    private final Coordinator coordinator;

    private MatchTrayOverlay trayOverlay;
    private volatile String lastHandledTitle;
    private volatile long lastHandledAtMillis;

    TitleHandler(Context context, WatchNowOverlay overlay, ExecutorService backgroundExecutor,
                 Handler mainHandler, Coordinator coordinator) {
        this.trayHostContext = context;
        this.appContext = context.getApplicationContext();
        this.overlay = overlay;
        this.backgroundExecutor = backgroundExecutor;
        this.mainHandler = mainHandler;
        this.coordinator = coordinator;
    }

    /** Whether the ambiguous-match chooser tray is currently up - see TvRelayAccessibilityService.handleVoiceSearchDetailPage(). */
    boolean isChooserShowing() {
        return trayOverlay != null && trayOverlay.isShowing();
    }

    /** Tear down the chooser tray, if any - call from the owner's onDestroy(). */
    void shutdown() {
        if (trayOverlay != null) {
            trayOverlay.hide();
        }
    }

    void handle(String title) {
        long now = System.currentTimeMillis();
        if (title.equals(lastHandledTitle) && (now - lastHandledAtMillis) < DEBOUNCE_MS) {
            return;
        }
        lastHandledTitle = title;
        lastHandledAtMillis = now;

        // Without the overlay permission, fall back to launching directly -
        // the user's explicit choice over doing nothing at all in that case.
        // With it, a confirm button appears as soon as possible (a loading
        // state now, swapped for "Watch now in {App}" once resolved) and
        // only tapping it launches anything - accidental-click protection,
        // which requires holding off on PlayerLauncher.open() until then.
        boolean confirmFirst = overlay != null && overlay.isPermissionGranted();
        if (confirmFirst) {
            overlay.showLoading();
            coordinator.onOverlayStateChanged();
            coordinator.onOverlayActivity();
        }

        PlayerApp app = Preferences.getSelectedApp(appContext);
        if (app.usesTitleSearch()) {
            // Plex/Jellyfin never have a *universal-catalog* content deep
            // link (see PlayerApp's class doc), so this always at least
            // falls back to a plain search hand-off with no
            // MetadataResolver call - see PlayerLauncher.prepareTitleSearch().
            // Jellyfin specifically may also open the title directly - see
            // PlayerLauncher.planTitleSearch(), which now makes one Jellyfin
            // request when Preferences.isJellyfinLibraryLookupReady() - so
            // this whole branch runs on backgroundExecutor.
            backgroundExecutor.execute(() -> {
                PlayerLauncher.TitleSearchPlan plan = PlayerLauncher.planTitleSearch(appContext, title);

                if (plan.foundInLibrary && Preferences.isChooserEnabled(appContext)
                        && MetadataResolver.isAmbiguous(plan.candidates)) {
                    Log.d(TAG, "Ambiguous Jellyfin library match for " + title
                            + " (" + plan.candidates.size() + " candidates)");
                    if (confirmFirst) {
                        mainHandler.post(() -> overlay.showConfirmAmbiguous(app,
                                () -> showChooser(title, plan.candidates)));
                    } else {
                        mainHandler.post(() -> showChooser(title, plan.candidates));
                    }
                    return;
                }

                if (confirmFirst) {
                    // showConfirm() ("Watch now in Jellyfin") when the title
                    // was actually found in the library, showConfirmSearch()
                    // ("Search in Jellyfin") otherwise.
                    if (plan.foundInLibrary) {
                        mainHandler.post(() -> overlay.showConfirm(app, plan.launch::getAsBoolean));
                    } else {
                        mainHandler.post(() -> overlay.showConfirmSearch(app, plan.launch::getAsBoolean));
                    }
                } else {
                    plan.launch.getAsBoolean();
                }
            });
            return;
        }

        backgroundExecutor.execute(() -> {
            List<TitleCandidate> candidates = MetadataResolver.resolveCandidates(appContext, title);
            if (candidates.isEmpty()) {
                Log.w(TAG, "Could not resolve an IMDB id for: " + title);
                if (confirmFirst) {
                    // abandon(), not hide(): resolution failed outright, so
                    // there's no pending match to ever offer back.
                    mainHandler.post(() -> {
                        overlay.abandon();
                        coordinator.onOverlayStateChanged();
                        coordinator.onOverlayActivity();
                    });
                }
                return;
            }

            if (Preferences.isChooserEnabled(appContext) && MetadataResolver.isAmbiguous(candidates)) {
                Log.d(TAG, "Ambiguous match for " + title + " (" + candidates.size() + " candidates)");
                if (confirmFirst) {
                    mainHandler.post(() -> overlay.showConfirmAmbiguous(app, () -> showChooser(title, candidates)));
                } else {
                    mainHandler.post(() -> showChooser(title, candidates));
                }
                return;
            }

            BooleanSupplier launch = PlayerLauncher.prepare(appContext, candidates.get(0));
            if (launch == null) {
                Log.w(TAG, "Could not resolve an IMDB id for: " + title);
                if (confirmFirst) {
                    mainHandler.post(() -> {
                        overlay.abandon();
                        coordinator.onOverlayStateChanged();
                        coordinator.onOverlayActivity();
                    });
                }
                return;
            }
            Log.d(TAG, "Resolved " + title);

            if (confirmFirst) {
                mainHandler.post(() -> overlay.showConfirm(app, launch::getAsBoolean));
            } else {
                launch.getAsBoolean();
            }
        });
    }

    /** Runs on the main thread - see the mainHandler.post() call sites above. */
    private void showChooser(String title, List<TitleCandidate> candidates) {
        if (trayOverlay == null) {
            trayOverlay = new MatchTrayOverlay(trayHostContext, new MatchTrayOverlay.Listener() {
                @Override
                public void onDismissed() {
                    coordinator.onOverlayActivity();
                }

                @Override
                public void onCancelled() {
                    // Backing out of the chooser is a temporary dismissal,
                    // same as BACK on the confirm button itself: the match is
                    // still pending on the (handoff-concealed) WatchNowOverlay,
                    // so resume its normal reappear cycle. No-op when nothing
                    // is concealed - see WatchNowOverlay.resumeAfterHandoff().
                    if (overlay != null) {
                        overlay.resumeAfterHandoff();
                    }
                }

                @Override
                public void onPicked(TitleCandidate candidate) {
                    // A real pick settles the pending match for good - the
                    // concealed confirm button must not reappear over the
                    // player that's about to launch.
                    if (overlay != null) {
                        overlay.abandon();
                        coordinator.onOverlayStateChanged();
                    }
                }
            });
        }
        trayOverlay.show(title, candidates);
        coordinator.onOverlayActivity();
    }
}
