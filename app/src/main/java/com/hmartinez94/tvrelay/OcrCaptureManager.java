package com.hmartinez94.tvrelay;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

/**
 * Screenshot+OCR fallback for launcher cards that carry zero accessible
 * text on their click event at all. MediaProjection is a wholly
 * separate, non-gated permission path from the AccessibilityService
 * capabilities that wall describes: the user grants it once via the
 * system's own screen-capture consent dialog, not an accessibility
 * capability, so it isn't affected by capabilities=0.
 *
 * Instantiated once by TvRelayAccessibilityService and reused for the
 * life of that service. Every public method here must be called on the
 * main thread, and every Callback is always invoked back on the main
 * thread too - this class does not do its own thread-hopping for callers,
 * it relies on the platform APIs it wraps (Activity results, Service
 * binding, ML Kit's default Task executor) already being main-thread by
 * default.
 *
 * Internal state machine: NO_SESSION -&gt; (consent Activity shown) -&gt;
 * SESSION_STARTING -&gt; (foreground service bound) -&gt; SESSION_ACTIVE -&gt;
 * (torn down, e.g. MediaProjection.Callback.onStop() in
 * OcrCaptureForegroundService) -&gt; back to NO_SESSION.
 *
 * On Android 14+, a MediaProjection token and its consent Intent are
 * strictly single-use - createVirtualDisplay() can only be called once
 * per token, and the same consent Intent can't be handed to
 * getMediaProjection() twice, or SecurityException is thrown. But the
 * resulting session (the VirtualDisplay/ImageReader actually producing
 * frames, owned by OcrCaptureForegroundService) stays alive and reusable
 * across many capture triggers until something tears it down. So the
 * design here is: request consent ONCE (the first call, or the first call
 * after a teardown), keep the session alive in that foreground service,
 * and reuse it for every subsequent requestTitleCapture() call with NO
 * new consent prompt, until the session is actually torn down.
 */
final class OcrCaptureManager {

    private static final String TAG = "OcrCaptureManager";

    // Extra sizes to read the (already 2x-upscaled) crop at, relative to it: 1.0x & 0.5x of the
    // original capture - see recognizeAtEachScale().
    private static final float[] RECOGNITION_EXTRA_SCALES = {0.5f, 0.25f};
    // Skip a variant whose crop would be too short to read at all.
    private static final int MIN_VARIANT_HEIGHT_PX = 40;

    private enum State {
        NO_SESSION, SESSION_STARTING, SESSION_ACTIVE
    }

    enum FailureReason {
        CONSENT_DENIED, NO_TEXT_FOUND, SESSION_UNAVAILABLE, CAPTURE_ERROR
    }

    interface Callback {
        /** Every distinct reading the recognizer produced, best first - never empty. */
        void onTitlesExtracted(java.util.List<String> titles);

        void onFailure(FailureReason reason);
    }

    /**
     * Single-slot "pending request" hook for OcrConsentActivity to report
     * its result through. OcrConsentActivity is a separate Android
     * component with no shared Activity/ViewModel to pass a normal
     * instance callback through, so this is a deliberate, narrow exception
     * to this codebase's usual all-static-methods style (see
     * Preferences.java, which has no instance state at all) - not a
     * general-purpose singleton. It's safe specifically because, by
     * construction, only one consent request is ever in flight at a time:
     * requestTitleCapture() only launches the consent Activity from
     * NO_SESSION, and immediately moves to SESSION_STARTING (and sets this
     * field) before the Activity even starts - a second call in that
     * window is rejected outright (see requestTitleCapture()) rather than
     * clobbering this field.
     */
    private static volatile OcrCaptureManager pendingConsentInstance;

    /**
     * Called by OcrConsentActivity once the user has responded to the
     * system consent dialog. granted=false covers both an explicit denial
     * and the user backing out without choosing anything - both mean "no
     * session, nothing to capture."
     */
    static void notifyConsentResult(boolean granted, int resultCode, Intent data) {
        OcrCaptureManager instance = pendingConsentInstance;
        pendingConsentInstance = null;
        if (instance == null) {
            Log.w(TAG, "notifyConsentResult() with no pending manager instance - ignoring");
            return;
        }
        instance.onConsentResult(granted, resultCode, data);
    }

    /**
     * Same kind of static bridge as pendingConsentInstance above, for the
     * same reason: PlayerLauncher (a static utility, no instance state at
     * all) needs to reach whichever OcrCaptureManager instance
     * TvRelayAccessibilityService currently owns - there's normally only
     * ever one alive at a time. Set whenever a session actually becomes
     * SESSION_ACTIVE, cleared in resetSession() - see
     * stopActiveSessionAfterLaunch().
     */
    private static volatile OcrCaptureManager activeSessionInstance;

    /**
     * Called by PlayerLauncher right after a launch succeeds - see its
     * stopOcrSessionIfRunning() javadoc for why. A no-op if no session is
     * currently active (or starting).
     */
    static void stopActiveSessionAfterLaunch() {
        OcrCaptureManager instance = activeSessionInstance;
        if (instance != null) {
            instance.stopSessionIfActive();
        }
    }

    // Real on-device testing (2026-08-25) found that a warm-session capture
    // (see class doc: no consent prompt, captureAndRecognize() runs
    // essentially the instant a trigger fires) can land mid-animation, while
    // the launcher's own detail-page entrance transition is still settling -
    // the very first cold-session capture (naturally delayed several
    // seconds by the consent dialog) always landed on the fully-settled
    // page and read correctly, but a later warm-session capture sometimes
    // read stray leftover text instead of the actual title, inconsistently
    // (the SAME crop region correctly read the title on other warm
    // captures). This delay gives the transition a chance to finish before
    // the frame is actually grabbed - see captureAndRecognize(). A round
    // guess, not tuned against a measured transition duration.
    private static final long CAPTURE_SETTLE_DELAY_MS = 1200;

    private final Context appContext;
    private final WatchNowOverlay overlay;
    private final TextRecognizer textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile State state = State.NO_SESSION;
    private Callback pendingCallback;
    private OcrCaptureForegroundService.CaptureBinder binder;
    private ServiceConnection serviceConnection;

    /** @param overlay used to force a fresh compositor frame right before each capture - see captureAndRecognize() and WatchNowOverlay.nudgeForFreshFrame(). */
    OcrCaptureManager(Context context, WatchNowOverlay overlay) {
        this.appContext = context.getApplicationContext();
        this.overlay = overlay;
    }

    /**
     * Requests the current on-screen title via screenshot+OCR. Main-thread
     * in, main-thread callback out - see class doc. Never throws; every
     * failure path (consent denial, a dead session, an exception anywhere
     * in the capture/bind/OCR sequence) reaches callback.onFailure()
     * instead, since this is an opt-in fallback feature and an uncaught
     * exception here must never propagate into the hosting
     * AccessibilityService process.
     */
    void requestTitleCapture(Callback callback) {
        try {
            requestTitleCaptureUnsafe(callback);
        } catch (Exception e) {
            Log.e(TAG, "requestTitleCapture() threw", e);
            state = State.NO_SESSION;
            pendingConsentInstance = null;
            Callback failed = pendingCallback;
            pendingCallback = null;
            safeFailure(failed != null ? failed : callback, FailureReason.CAPTURE_ERROR);
        }
    }

    private void requestTitleCaptureUnsafe(Callback callback) {
        if (state == State.SESSION_ACTIVE && binder != null) {
            captureAndRecognize(callback);
            return;
        }

        if (state == State.SESSION_STARTING) {
            // Already mid-handshake from an earlier trigger - fail this
            // particular request rather than queueing it. This is an
            // opt-in fallback feature; a real multi-request queue isn't
            // worth the complexity for what should be a rare double-click.
            Log.d(TAG, "requestTitleCapture() while a session is already starting - failing this request");
            safeFailure(callback, FailureReason.SESSION_UNAVAILABLE);
            return;
        }

        Log.d(TAG, "No active OCR session - requesting screen-capture consent");
        state = State.SESSION_STARTING;
        pendingCallback = callback;
        pendingConsentInstance = this;

        Intent intent = new Intent(appContext, OcrConsentActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        appContext.startActivity(intent);
    }

    /**
     * Establishes a capture session (requesting the one-time screen-capture
     * consent if needed) WITHOUT taking a capture. Used by
     * FireTvWatcherService to prime the session the moment Fire TV mode is
     * activated: this brings up OcrCaptureForegroundService (a foreground
     * service), which both keeps the process alive while the watcher polls
     * and makes the first real detail-page capture warm (no consent prompt
     * mid-selection). A no-op if a session is already active or starting.
     * Same threading contract as requestTitleCapture() - call on the main
     * thread; never throws (an opt-in feature must not crash its host).
     */
    void ensureSession() {
        try {
            if (state == State.SESSION_ACTIVE || state == State.SESSION_STARTING) {
                return;
            }
            Log.d(TAG, "Priming OCR session (no capture) - requesting screen-capture consent");
            state = State.SESSION_STARTING;
            pendingCallback = null;
            pendingConsentInstance = this;
            Intent intent = new Intent(appContext, OcrConsentActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            appContext.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "ensureSession() threw", e);
            state = State.NO_SESSION;
            if (pendingConsentInstance == this) {
                pendingConsentInstance = null;
            }
            pendingCallback = null;
        }
    }

    /** Tears down any live session. Call from the hosting Service's onDestroy(). */
    void shutdown() {
        Log.d(TAG, "Shutting down");
        if (pendingConsentInstance == this) {
            pendingConsentInstance = null;
        }
        pendingCallback = null;
        resetSession();
        try {
            textRecognizer.close();
        } catch (Exception e) {
            Log.e(TAG, "Error closing text recognizer", e);
        }
        // OcrCaptureForegroundService is independently *started*, not just
        // bound (see class doc), so it - and the persistent notification
        // it shows - would otherwise keep running in the background after
        // this manager (and the AccessibilityService that owns it) is
        // gone. Explicitly stop it here rather than relying on unbind.
        try {
            appContext.stopService(new Intent(appContext, OcrCaptureForegroundService.class));
        } catch (Exception e) {
            Log.e(TAG, "Error stopping OcrCaptureForegroundService", e);
        }
    }

    private void onConsentResult(boolean granted, int resultCode, Intent data) {
        try {
            onConsentResultUnsafe(granted, resultCode, data);
        } catch (Exception e) {
            Log.e(TAG, "onConsentResult() threw", e);
            state = State.NO_SESSION;
            Callback callback = pendingCallback;
            pendingCallback = null;
            safeFailure(callback, FailureReason.CAPTURE_ERROR);
        }
    }

    private void onConsentResultUnsafe(boolean granted, int resultCode, Intent data) {
        if (!granted) {
            Log.d(TAG, "OCR consent denied");
            state = State.NO_SESSION;
            Callback callback = pendingCallback;
            pendingCallback = null;
            safeFailure(callback, FailureReason.CONSENT_DENIED);
            return;
        }

        // OcrConsentActivity has already started OcrCaptureForegroundService
        // (passing resultCode/data as extras) by the time this runs - this
        // manager only needs to bind to it, not start it a second time.
        // BIND_AUTO_CREATE is used defensively against a start/bind race,
        // but the service's lifetime is governed by its started state
        // (see shutdown()/OcrCaptureForegroundService's class doc), not by
        // this binding - unbinding here later does not stop the session.
        serviceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                Log.d(TAG, "OCR capture session bound and active");
                binder = (OcrCaptureForegroundService.CaptureBinder) service;
                state = State.SESSION_ACTIVE;
                activeSessionInstance = OcrCaptureManager.this;
                Callback callback = pendingCallback;
                pendingCallback = null;
                if (callback != null) {
                    captureAndRecognize(callback);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                // Session torn down out from under us (e.g. the user
                // stopped it via the system's persistent recording
                // notification, or the service process died) - reset so
                // the NEXT trigger re-requests consent instead of
                // silently failing forever against a dead binder.
                Log.d(TAG, "OCR capture session disconnected - resetting to NO_SESSION");
                resetSession();
            }
        };

        boolean bound = appContext.bindService(
                new Intent(appContext, OcrCaptureForegroundService.class),
                serviceConnection, Context.BIND_AUTO_CREATE);
        if (!bound) {
            Log.e(TAG, "Failed to bind OcrCaptureForegroundService");
            serviceConnection = null;
            state = State.NO_SESSION;
            Callback callback = pendingCallback;
            pendingCallback = null;
            safeFailure(callback, FailureReason.SESSION_UNAVAILABLE);
        }
    }

    private void captureAndRecognize(Callback callback) {
        if (state != State.SESSION_ACTIVE || binder == null) {
            safeFailure(callback, FailureReason.SESSION_UNAVAILABLE);
            return;
        }
        // See CAPTURE_SETTLE_DELAY_MS's javadoc - waits for the launcher's
        // detail-page entrance transition to finish before grabbing a frame,
        // rather than capturing whatever's on screen the instant this runs.
        mainHandler.postDelayed(() -> {
            if (state != State.SESSION_ACTIVE || binder == null) {
                safeFailure(callback, FailureReason.SESSION_UNAVAILABLE);
                return;
            }
            binder.captureFrame(new OcrCaptureForegroundService.FrameCallback() {
                @Override
                public void onFrameCaptured(Bitmap bitmap) {
                    runTextRecognition(bitmap, callback);
                }

                @Override
                public void onFrameError() {
                    Log.w(TAG, "Frame capture failed");
                    safeFailure(callback, FailureReason.CAPTURE_ERROR);
                }
            });
            // MUST come after captureFrame() above, not before - see
            // WatchNowOverlay.nudgeForFreshFrame()'s javadoc for exactly
            // why the ordering matters (imageAvailableListener drains and
            // closes any frame produced before pendingFrameCallback is
            // armed, so a nudge fired too early is wasted). By the time
            // this line runs, captureFrame() has already synchronously
            // armed its callback on this same thread - see
            // OcrCaptureForegroundService.captureFrame()'s compareAndSet,
            // which happens before that method returns.
            if (overlay != null) {
                overlay.nudgeForFreshFrame();
            }
        }, CAPTURE_SETTLE_DELAY_MS);
    }

    private void runTextRecognition(Bitmap bitmap, Callback callback) {
        if (bitmap == null) {
            safeFailure(callback, FailureReason.CAPTURE_ERROR);
            return;
        }
        try {
            InputImage image = InputImage.fromBitmap(bitmap, 0);
            // Resolved HERE rather than inside the success listener below:
            // this method runs on the main thread (FrameCallback is always
            // delivered there - see OcrCaptureForegroundService.deliverFrame),
            // so the lookup happens on a known thread and the array is just
            // an effectively-final local captured by the lambda.
            String[] chromeDenylist = LauncherLocale.systemResources(appContext)
                    .getStringArray(R.array.ocr_chrome_denylist);
            recognizeAtEachScale(bitmap, chromeDenylist, callback);
        } catch (Exception e) {
            Log.e(TAG, "OCR pipeline threw", e);
            safeFailure(callback, FailureReason.CAPTURE_ERROR);
        }
    }

    /**
     * Reads the crop at three sizes and reports every distinct title seen.
     *
     * ML Kit is tuned for characters of roughly 16-24 px and gains nothing
     * from larger ones (its own documentation), yet the title on a Google TV
     * detail page is ~90 px tall at capture size and ~180 px after the crop
     * upscale - and one-letter misreads followed ("You Should Have Lef",
     * "The Babadool"). Which size reads a given title best isn't knowable
     * up front, so all three are tried and TitleHandler picks the reading
     * that actually matches a real title. Sequential, not parallel - one
     * recognizer instance, and the whole thing is a few hundred ms.
     *
     * Readings that agree rank first: a string seen at more sizes is more
     * likely right, with the order tried (largest first, the one validated
     * before this existed) breaking ties.
     */
    private void recognizeAtEachScale(Bitmap source, String[] chromeDenylist, Callback callback) {
        java.util.List<Bitmap> variants = new java.util.ArrayList<>();
        variants.add(source);
        for (float scale : RECOGNITION_EXTRA_SCALES) {
            int w = Math.round(source.getWidth() * scale);
            int h = Math.round(source.getHeight() * scale);
            if (h >= MIN_VARIANT_HEIGHT_PX && w > 0) {
                variants.add(Bitmap.createScaledBitmap(source, w, h, true));
            }
        }
        recognizeVariant(variants, 0, chromeDenylist, new java.util.ArrayList<>(), callback);
    }

    private void recognizeVariant(java.util.List<Bitmap> variants, int index, String[] chromeDenylist,
                                  java.util.List<String> readings, Callback callback) {
        if (index >= variants.size()) {
            for (int i = 1; i < variants.size(); i++) {
                variants.get(i).recycle();
            }
            finishReadings(readings, callback);
            return;
        }
        try {
            textRecognizer.process(InputImage.fromBitmap(variants.get(index), 0))
                    .addOnSuccessListener(text -> {
                        String title = OcrTextCleaner.extractTitle(text, chromeDenylist);
                        if (title != null) {
                            readings.add(title);
                        }
                        recognizeVariant(variants, index + 1, chromeDenylist, readings, callback);
                    })
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "ML Kit failed on variant " + index, e);
                        recognizeVariant(variants, index + 1, chromeDenylist, readings, callback);
                    });
        } catch (Exception e) {
            Log.e(TAG, "OCR pipeline threw", e);
            recognizeVariant(variants, index + 1, chromeDenylist, readings, callback);
        }
    }

    private void finishReadings(java.util.List<String> readings, Callback callback) {
        java.util.Map<String, Integer> votes = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> firstSpelling = new java.util.LinkedHashMap<>();
        for (String reading : readings) {
            String key = OcrMatchFilter.normalize(reading);
            if (key.isEmpty()) {
                continue;
            }
            Integer seen = votes.get(key);
            votes.put(key, seen == null ? 1 : seen + 1);
            if (!firstSpelling.containsKey(key)) {
                firstSpelling.put(key, reading);
            }
        }
        if (votes.isEmpty()) {
            Log.d(TAG, "OCR found no usable text");
            safeFailure(callback, FailureReason.NO_TEXT_FOUND);
            return;
        }
        java.util.List<String> keys = new java.util.ArrayList<>(votes.keySet());
        // Stable sort: equal votes keep the order they were first read in.
        java.util.Collections.sort(keys, (a, b) -> votes.get(b) - votes.get(a));
        java.util.List<String> titles = new java.util.ArrayList<>();
        for (String key : keys) {
            titles.add(firstSpelling.get(key));
        }
        Log.d(TAG, "OCR readings (best first): " + titles);
        safeSuccess(callback, titles);
    }

    private void resetSession() {
        state = State.NO_SESSION;
        binder = null;
        if (activeSessionInstance == this) {
            activeSessionInstance = null;
        }
        if (serviceConnection != null) {
            try {
                appContext.unbindService(serviceConnection);
            } catch (Exception e) {
                // Already unbound - nothing to do.
            }
            serviceConnection = null;
        }
    }

    /**
     * Tears down a live session in response to a successful launch
     * elsewhere (see stopActiveSessionAfterLaunch()) - unbinds, stops the
     * foreground service, and resets state, but (unlike shutdown()) does
     * NOT close the text recognizer or touch pendingConsentInstance, since
     * the owning TvRelayAccessibilityService is still alive and may trigger
     * OCR again later - that will simply request a fresh consent grant,
     * same as the very first time (see class doc). A no-op if no session
     * is currently active or starting.
     */
    private void stopSessionIfActive() {
        if (state == State.NO_SESSION) {
            return;
        }
        Log.d(TAG, "Stopping OCR session: a launch succeeded, leaving the lobby");
        resetSession();
        try {
            appContext.stopService(new Intent(appContext, OcrCaptureForegroundService.class));
        } catch (Exception e) {
            Log.e(TAG, "Error stopping OcrCaptureForegroundService", e);
        }
    }

    private void safeSuccess(Callback callback, java.util.List<String> titles) {
        if (callback == null) {
            return;
        }
        try {
            callback.onTitlesExtracted(titles);
        } catch (Exception e) {
            Log.e(TAG, "Callback.onTitlesExtracted threw", e);
        }
    }

    private void safeFailure(Callback callback, FailureReason reason) {
        if (callback == null) {
            return;
        }
        try {
            callback.onFailure(reason);
        } catch (Exception e) {
            Log.e(TAG, "Callback.onFailure threw", e);
        }
    }
}
