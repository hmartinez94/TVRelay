package com.hmartinez94.tvrelay;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the actual MediaProjection/VirtualDisplay/ImageReader lifecycle for
 * the OCR capture fallback - see OcrCaptureManager for the state machine
 * that drives this, and "The capabilities wall" in CLAUDE.md for why this
 * exists at all (the AccessibilityService can't retrieve window content or
 * take its own screenshot; MediaProjection is a wholly separate,
 * non-gated permission path).
 *
 * Lifecycle, driven entirely by OcrConsentActivity/OcrCaptureManager:
 *  - Started (ContextCompat.startForegroundService) by OcrConsentActivity
 *    once the user grants the one-time screen-capture consent, carrying
 *    the resultCode/Intent that consent produced as extras. Per Android
 *    14's ordering requirement, onStartCommand() calls startForeground()
 *    FIRST, then MediaProjectionManager.getMediaProjection() - the reverse
 *    order throws SecurityException/MissingForegroundServiceTypeException.
 *  - The MediaProjection token and consent Intent are single-use
 *    (getMediaProjection()/createVirtualDisplay() each throw
 *    SecurityException on a second call on Android 14+), so the
 *    VirtualDisplay/ImageReader are created exactly once here, in
 *    startProjectionSession(), and then reused for every subsequent
 *    capture - see captureFrame() - until MediaProjection.Callback.onStop()
 *    tears everything down (user stops it via the system's persistent
 *    recording notification, or this service is otherwise killed).
 *  - Also bound (Context.bindService) by OcrCaptureManager, independently
 *    of the start above - this service is deliberately both started AND
 *    bound so it keeps running (and the MediaProjection session stays
 *    alive) even if OcrCaptureManager unbinds and rebinds later; only the
 *    started state controls its lifetime.
 */
public final class OcrCaptureForegroundService extends Service {

    private static final String TAG = "OcrCaptureFgService";

    static final String EXTRA_RESULT_CODE = "com.hmartinez94.tvrelay.extra.RESULT_CODE";
    static final String EXTRA_RESULT_DATA = "com.hmartinez94.tvrelay.extra.RESULT_DATA";

    private static final String CHANNEL_ID = "ocr_capture";
    private static final int NOTIFICATION_ID = 7301;

    // Confirmed real bug (2026-08-25), a deeper case than
    // tryAcquireBufferedFrame() alone fixes: Android's compositor only
    // produces a new mirrored frame when something on screen actually
    // changes ("damage") - if the screen has been fully static for a while
    // (the buffered frame from the last change already consumed and closed,
    // nothing since), BOTH acquireLatestImage() and onImageAvailable() can
    // go silent indefinitely, since there is genuinely no new frame for
    // either to find. Reproduced on-device: a capture sat with no result at
    // all for 16+ seconds, resolving only the instant the user pressed a
    // key (which redrew the screen - the overlay button's own visibility
    // change counts as damage). This can't be fixed by waiting smarter -
    // there may really be nothing to wait for - so this bounds the wait
    // instead: fail the request outright if no frame arrives in time,
    // rather than leaving OcrCaptureManager's caller (and the "Looking that
    // up" button) hanging forever. See captureFrame()/timeoutIfStillPending().
    private static final long CAPTURE_TIMEOUT_MS = 4000;

    /** Result of a single requested capture - always delivered on the main thread. */
    interface FrameCallback {
        void onFrameCaptured(Bitmap bitmap);

        void onFrameError();
    }

    final class CaptureBinder extends Binder {
        void captureFrame(FrameCallback callback) {
            OcrCaptureForegroundService.this.captureFrame(callback);
        }
    }

    private final CaptureBinder binder = new CaptureBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Set once per session in startProjectionSession() (main thread, from
    // onStartCommand), torn down together in teardownSession() - which can
    // run from EITHER the main thread (onDestroy()) OR captureHandler's
    // thread (projectionCallback.onStop(), since that callback is
    // registered with captureHandler - see startProjectionSession()).
    // volatile so a teardown on one thread is reliably visible to
    // captureFrame()'s null-checks on the other.
    private volatile MediaProjection mediaProjection;
    private volatile VirtualDisplay virtualDisplay;
    private volatile ImageReader imageReader;
    private volatile HandlerThread captureThread;
    private volatile Handler captureHandler;

    // The ImageReader's listener runs on captureHandler and drains every
    // frame the VirtualDisplay produces regardless of whether a capture is
    // pending (see imageAvailableListener below) - this is what a pending
    // capture is armed against. Accessed from both the main thread
    // (captureFrame(), arming it) and captureHandler (consuming it), hence
    // the atomic reference rather than a plain field.
    private final AtomicReference<FrameCallback> pendingFrameCallback = new AtomicReference<>();

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            Log.d(TAG, "MediaProjection stopped (system notification, revoked consent, etc.) - tearing down");
            teardownSession();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Must happen before touching MediaProjectionManager at all - see
        // class doc's ordering note. Safe to call again on a later
        // onStartCommand (e.g. if the OS restarts delivery of an old
        // intent); it's idempotent enough for a foreground service.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification());
        }

        if (mediaProjection != null) {
            // A session is already live from an earlier onStartCommand.
            // The MediaProjection token/consent Intent can only be
            // consumed once (see class doc), so there's nothing to do
            // with a second set of extras even if one somehow arrives -
            // OcrCaptureManager only re-triggers the consent flow after a
            // full teardown, but guard against it anyway.
            Log.d(TAG, "onStartCommand with a session already active - ignoring extras");
            return START_NOT_STICKY;
        }

        if (intent == null) {
            Log.e(TAG, "onStartCommand with no intent - cannot start a MediaProjection session");
            stopSelf();
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        @SuppressWarnings("deprecation")
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            Log.e(TAG, "onStartCommand missing a valid consent result - cannot start a session");
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            startProjectionSession(resultCode, resultData);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start MediaProjection session", e);
            teardownSession();
            stopSelf();
        }

        // Not START_STICKY: if the process is killed, there's no saved
        // consent Intent to restart the session with anyway - the next
        // capture request has to go through OcrConsentActivity again
        // regardless (see OcrCaptureManager.resetSession() on disconnect).
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        teardownSession();
        super.onDestroy();
    }

    private void startProjectionSession(int resultCode, Intent resultData) {
        MediaProjectionManager projectionManager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (projectionManager == null) {
            Log.e(TAG, "MediaProjectionManager unavailable");
            stopSelf();
            return;
        }

        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData);
        if (mediaProjection == null) {
            Log.e(TAG, "getMediaProjection() returned null");
            stopSelf();
            return;
        }

        HandlerThread thread = new HandlerThread("TvRelayOcrCapture");
        thread.start();
        captureThread = thread;
        captureHandler = new Handler(thread.getLooper());

        mediaProjection.registerCallback(projectionCallback, captureHandler);

        imageReader = ImageReader.newInstance(
                OcrCaptureConfig.CAPTURE_WIDTH,
                OcrCaptureConfig.CAPTURE_HEIGHT,
                PixelFormat.RGBA_8888,
                /* maxImages */ 2);
        imageReader.setOnImageAvailableListener(imageAvailableListener, captureHandler);

        int densityDpi = getResources().getDisplayMetrics().densityDpi;
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "TVRelayOcrCapture",
                OcrCaptureConfig.CAPTURE_WIDTH,
                OcrCaptureConfig.CAPTURE_HEIGHT,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                /* callback */ null,
                captureHandler);

        Log.d(TAG, "MediaProjection session started at "
                + OcrCaptureConfig.CAPTURE_WIDTH + "x" + OcrCaptureConfig.CAPTURE_HEIGHT);
    }

    private void teardownSession() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.setOnImageAvailableListener(null, null);
            imageReader.close();
            imageReader = null;
        }
        if (mediaProjection != null) {
            try {
                mediaProjection.unregisterCallback(projectionCallback);
            } catch (Exception e) {
                // Best effort - projection may already be torn down.
            }
            mediaProjection.stop();
            mediaProjection = null;
        }
        if (captureThread != null) {
            captureThread.quitSafely();
            captureThread = null;
            captureHandler = null;
        }
        FrameCallback stranded = pendingFrameCallback.getAndSet(null);
        deliverError(stranded);
    }

    /**
     * Called on the main thread by CaptureBinder - arms the next frame the
     * ImageReader produces to be delivered instead of drained, AND
     * immediately tries to grab whatever frame is already buffered (see
     * tryAcquireBufferedFrame()) rather than only ever waiting passively for
     * a brand new one.
     *
     * That immediate attempt is not an optimization - it's required for
     * correctness. Confirmed real bug on-device (2026-08-25): once the
     * launcher's detail-page entrance animation finishes and the mirrored
     * display settles into a fully static frame, the OS can stop producing
     * new frames from the VirtualDisplay entirely - onImageAvailable()
     * simply never fires again - so a capture requested after that point
     * hung indefinitely with no error, no timeout, nothing, until the user
     * pressed a key (which redrew the screen and produced a new frame,
     * unblocking it). Trying acquireLatestImage() immediately sidesteps
     * this: it returns whatever the mirror already produced (the settled,
     * fully-transitioned frame we actually want to OCR) without needing a
     * NEW frame to arrive at all.
     *
     * Also arms a CAPTURE_TIMEOUT_MS timeout (see that constant's javadoc) -
     * even the immediate-acquire attempt above can still find nothing
     * buffered and fall back to waiting on a frame that may never come, if
     * the screen has been fully static since before this request started.
     */
    private void captureFrame(FrameCallback callback) {
        if (imageReader == null || mediaProjection == null || captureHandler == null) {
            Log.w(TAG, "captureFrame() with no active session");
            deliverError(callback);
            return;
        }
        if (!pendingFrameCallback.compareAndSet(null, callback)) {
            Log.w(TAG, "captureFrame() called while a capture is already in flight - ignoring");
            deliverError(callback);
            return;
        }
        captureHandler.post(this::tryAcquireBufferedFrame);
        captureHandler.postDelayed(() -> timeoutIfStillPending(callback), CAPTURE_TIMEOUT_MS);
    }

    /**
     * Fails a capture that's been waiting too long for a frame - see
     * CAPTURE_TIMEOUT_MS's javadoc. compareAndSet (not a plain null-check)
     * is what makes this safe to race against a real, late-arriving frame:
     * if the frame already won (imageAvailableListener/
     * tryAcquireBufferedFrame already cleared pendingFrameCallback via
     * getAndSet(null)), this finds the field no longer equal to `callback`
     * and does nothing - no double delivery either way, regardless of which
     * one runs first.
     */
    private void timeoutIfStillPending(FrameCallback callback) {
        if (pendingFrameCallback.compareAndSet(callback, null)) {
            Log.w(TAG, "Capture timed out after " + CAPTURE_TIMEOUT_MS
                    + "ms with no frame produced - screen is likely fully static");
            deliverError(callback);
        }
    }

    /**
     * Runs on captureHandler, same thread as imageAvailableListener (a
     * single-threaded HandlerThread, so the two can never race each other
     * over the ImageReader). If nothing is buffered yet, this is a no-op -
     * pendingFrameCallback stays armed and imageAvailableListener delivers
     * it whenever a frame actually does arrive, same as before this existed.
     */
    private void tryAcquireBufferedFrame() {
        ImageReader reader = imageReader;
        if (reader == null) {
            return;
        }
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) {
                return;
            }
            FrameCallback callback = pendingFrameCallback.getAndSet(null);
            if (callback == null) {
                // imageAvailableListener already delivered a frame for this
                // request in the brief window before this ran - nothing left
                // to do with this one.
                return;
            }
            Bitmap bitmap = imageToBitmap(image);
            deliverFrame(callback, bitmap);
        } catch (Exception e) {
            Log.e(TAG, "Error acquiring buffered frame", e);
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    // Runs on captureHandler (the dedicated capture HandlerThread), not the
    // main thread - drains every frame the continuously-mirroring
    // VirtualDisplay produces, whether or not a capture is currently
    // pending, so the small (maxImages=2) ImageReader buffer queue never
    // backs up the producer. Only converts/delivers a frame when
    // pendingFrameCallback is actually armed by captureFrame() above.
    private final ImageReader.OnImageAvailableListener imageAvailableListener = reader -> {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) {
                return;
            }
            FrameCallback callback = pendingFrameCallback.getAndSet(null);
            if (callback == null) {
                return;
            }
            Bitmap bitmap = imageToBitmap(image);
            deliverFrame(callback, bitmap);
        } catch (Exception e) {
            Log.e(TAG, "Error handling captured frame", e);
            deliverError(pendingFrameCallback.getAndSet(null));
        } finally {
            if (image != null) {
                image.close();
            }
        }
    };

    private void deliverFrame(FrameCallback callback, Bitmap bitmap) {
        if (callback == null) {
            return;
        }
        Bitmap cropped;
        try {
            cropped = cropToConfig(bitmap);
        } catch (Exception e) {
            Log.e(TAG, "Failed to crop captured frame", e);
            deliverError(callback);
            return;
        }
        mainHandler.post(() -> callback.onFrameCaptured(cropped));
    }

    private void deliverError(FrameCallback callback) {
        if (callback == null) {
            return;
        }
        mainHandler.post(callback::onFrameError);
    }

    /**
     * PixelFormat.RGBA_8888 ImageReader frames are row-stride-padded, not a
     * tight width*height buffer - a naive copyPixelsFromBuffer() at the
     * requested width silently shears the image if the stride doesn't
     * match. Build the Bitmap at the padded (stride-derived) width first,
     * then trim back down to the real capture dimensions.
     */
    private static Bitmap imageToBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();

        Bitmap padded = Bitmap.createBitmap(
                image.getWidth() + rowPadding / pixelStride,
                image.getHeight(),
                Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);

        if (rowPadding == 0) {
            return padded;
        }
        Bitmap trimmed = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
        padded.recycle();
        return trimmed;
    }

    /** Crops the already-downscaled (CAPTURE_WIDTH x CAPTURE_HEIGHT) bitmap per OcrCaptureConfig's fractional band - see its class doc. */
    private static Bitmap cropToConfig(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int left = clamp((int) (width * OcrCaptureConfig.CROP_LEFT), 0, width);
        int top = clamp((int) (height * OcrCaptureConfig.CROP_TOP), 0, height);
        int right = clamp((int) (width * OcrCaptureConfig.CROP_RIGHT), left, width);
        int bottom = clamp((int) (height * OcrCaptureConfig.CROP_BOTTOM), top, height);
        int cropWidth = Math.max(1, right - left);
        int cropHeight = Math.max(1, bottom - top);
        return Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Notification text is a plain string literal rather than a
     * strings.xml resource on purpose - strings.xml is owned by a
     * concurrently-running agent for this same feature (Settings UI /
     * AccessibilityService wiring), so touching it here would collide.
     * This is a real, distinct disclosure surface from the OS's own
     * screen-recording indicator (which MediaProjection already shows
     * independently) - both are visible whenever a session is live.
     */
    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "TVRelay screen reading", NotificationManager.IMPORTANCE_LOW);
                manager.createNotificationChannel(channel);
            }
        }

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("TVRelay screen-reading is active")
                .setContentText("Reading the screen to identify a title TVRelay couldn't detect normally.")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}
