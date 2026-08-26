package com.hmartinez94.tvrelay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;

/**
 * Floating confirmation button shown over whatever screen the launcher
 * navigates to after a recognized click. Two states:
 *  - loading: shown immediately once a click is recognized, before
 *    TheTVDB resolution completes - so the button appears "as soon as
 *    possible" rather than only once the network call finishes.
 *  - confirm: "Watch now in {App}" once resolved. Tapping it - and only
 *    tapping it - launches the title. Never launches on its own: the
 *    accidental-click protection this feature exists for in the first
 *    place comes from requiring that explicit tap, not from any timeout.
 *
 * A directional/BACK key press now only CONCEALS the button rather than
 * discarding the pending match - it reappears a few seconds later, and this
 * hide/reappear cycle repeats each time the user dismisses it again, for as
 * long as the user hasn't navigated back to the launcher's home/lobby
 * screen. There is deliberately no time-based auto-dismiss any more (an
 * earlier version hid the button after 10s of pure inactivity; removed
 * 2026-08-25 - it fired even while the user was just looking at the screen
 * without touching the remote, and also fed a real self-sustaining loop with
 * the voice-search OCR fallback, see TvRelayAccessibilityService's
 * handleVoiceSearchDetailPage() SECOND CORRECTION). Concealing now only ever
 * happens in direct response to a key press. This is a deliberate behavior
 * change from the original implementation, where any dismissal was
 * permanent. The pending match is fully and permanently
 * abandoned (see abandon()) when:
 *  (a) the user actually returns to the launcher lobby - detected via
 *      TvRelayAccessibilityService's window-state observation. This
 *      detection is a best-effort heuristic, not a confirmed mechanism -
 *      see LOBBY_CLASS_MARKER's javadoc in that class for exactly how
 *      unverified it currently is;
 *  (b) Home is pressed - reliably means "at the lobby" without needing the
 *      heuristic above;
 *  (c) the user taps confirm - directly for a normal/search confirm, or,
 *      for an ambiguous-match confirm, only once a candidate is actually
 *      picked in the chooser tray: tapping the ambiguous confirm itself
 *      merely conceals the button while MatchTrayOverlay takes over, so
 *      backing out of the tray can resume the reappear cycle - see
 *      concealForHandoff()/resumeAfterHandoff(); or
 *  (d) the defensive MAX_LIFETIME_MS cap is hit, in case (a) never fires.
 *
 * Requires the "Display over other apps" permission (SYSTEM_ALERT_WINDOW).
 * Callers must check isPermissionGranted() themselves and fall back to
 * launching directly when it's false - see TvRelayAccessibilityService.
 */
final class WatchNowOverlay {

    private static final String TAG = "WatchNowOverlay";

    // How long the button stays concealed before reappearing - "a few
    // seconds of continued inactivity." Not tuned against real usage yet;
    // a round guess.
    private static final long REAPPEAR_DELAY_MS = 4_000;

    // Defensive absolute cap on how long a match can stay pending in total,
    // in case the lobby-detection heuristic (see
    // TvRelayAccessibilityService.LOBBY_CLASS_MARKER) never fires for a
    // given launcher build. Generous on purpose - this is a safety net, not
    // the primary abandonment mechanism.
    private static final long MAX_LIFETIME_MS = 300_000;

    private enum State { IDLE, SHOWING, HIDDEN }

    private final Context appContext;
    private final WindowManager windowManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private State state = State.IDLE;
    private Button button;
    private WindowManager.LayoutParams params;
    // The pending reappear callback while HIDDEN - nothing is ever
    // scheduled while SHOWING any more, since concealing only ever happens
    // in direct response to a key press, not a timeout.
    private Runnable cycleTimer;
    // Separate from cycleTimer: bounds total pending-match lifetime, not
    // per-visible-segment lifetime, so hide/reappear cycles must NOT reset
    // it. Only abandon() cancels it.
    private Runnable lifetimeCapTimer;
    private BroadcastReceiver homeReceiver;

    WatchNowOverlay(Context context) {
        this.appContext = context.getApplicationContext();
        this.windowManager = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
    }

    boolean isPermissionGranted() {
        return Settings.canDrawOverlays(appContext);
    }

    /** Shown the instant a click is recognized, before resolution finishes. */
    void showLoading() {
        if (state != State.IDLE) {
            // A fresh click supersedes whatever was pending - matches the
            // implicit overwrite behavior of the original implementation
            // (calling ensureButton() again just reused the same button).
            Log.d(TAG, "Abandoning stale pending match: new click superseded it");
            abandon();
        }
        ensureButton();
        if (button == null) {
            return;
        }
        button.setText(R.string.watch_now_loading);
        button.setOnClickListener(null);
    }

    /** Swaps to the real confirm state once resolution completes (or, for the Nuvio+TMDB fast path, needed no resolution at all - see PlayerLauncher.prepare). */
    void showConfirm(PlayerApp app, Runnable onConfirm) {
        Log.d(TAG, "Showing confirm via " + app.getLabel());
        showConfirmWithText(appContext.getString(R.string.watch_now_confirm, app.getLabel()), false, onConfirm);
    }

    /**
     * Same confirm step, but for a title that turned out to have more than
     * one exact match (see MetadataResolver.isAmbiguous) - there's no
     * single resolved match yet to name in the button, so "onConfirm" is
     * expected to show the chooser rather than launch anything directly.
     * Because of that, tapping this confirm is a HANDOFF, not a settlement:
     * the pending match is concealed rather than abandoned - see
     * concealForHandoff().
     */
    void showConfirmAmbiguous(PlayerApp app, Runnable onConfirm) {
        Log.d(TAG, "Showing ambiguous-match confirm via " + app.getLabel());
        showConfirmWithText(appContext.getString(R.string.watch_now_choose, app.getLabel()), true, onConfirm);
    }

    /**
     * Same confirm step, but honestly labeled "Search in {App}" rather than
     * "Watch now" - for a title-search player (Plex, Jellyfin), which has
     * no content deep link and only ever opens a search screen, not the
     * title itself. See PlayerApp.usesTitleSearch() / PlayerLauncher.
     */
    void showConfirmSearch(PlayerApp app, Runnable onConfirm) {
        showConfirmSearch(app.getLabel(), onConfirm);
    }

    /**
     * Same as showConfirmSearch(PlayerApp, Runnable), but for a target that
     * isn't a PlayerApp at all - namely SmartTube's YouTube redirect (see
     * PlayerLauncher.prepareSmartTube()), which is an independent toggle,
     * not one of the mutually-exclusive player choices in Settings.
     */
    void showConfirmSearch(String appLabel, Runnable onConfirm) {
        Log.d(TAG, "Showing search confirm via " + appLabel);
        showConfirmWithText(appContext.getString(R.string.watch_now_search, appLabel), false, onConfirm);
    }

    private void showConfirmWithText(String text, boolean handoffToChooser, Runnable onConfirm) {
        if (button == null) {
            state = State.IDLE;
        }
        // Tapping a normal/search confirm settles the match for good:
        // abandon, then launch. Tapping the ambiguous confirm instead only
        // hands off to the chooser tray - the match stays pending, concealed,
        // so backing out of the tray can bring the button back (see
        // concealForHandoff()). It used to abandon() unconditionally here,
        // which left NOTHING pending once the chooser was up - so BACK on
        // the chooser stranded the user with no button and no reappear,
        // ever (confirmed real bug, 2026-08-26, for both click- and
        // OCR-detected titles - both funnel through the same path).
        View.OnClickListener onClick = v -> {
            if (handoffToChooser) {
                concealForHandoff();
            } else {
                abandon();
            }
            onConfirm.run();
        };
        switch (state) {
            case IDLE:
                // The match may have already been abandoned (Home pressed,
                // lobby detected, or the lifetime cap hit) while background
                // resolution was still running. Deliberately a no-op here -
                // this is a beneficial side effect of adding explicit
                // state: it prevents resurrecting a window after the user
                // already left, which was a latent possibility in the
                // original implementation (ensureButton() would have
                // silently recreated the window at this point).
                return;
            case HIDDEN:
                // Update the (currently invisible) button's text/listener
                // only - do NOT reveal() it and do NOT touch the
                // currently-running reappear timer. This is what stops a
                // network resolution completing while hidden from undoing
                // the user's decision to dismiss; the new text/listener
                // simply surfaces naturally at the next scheduled reappear.
                button.setText(text);
                button.setOnClickListener(onClick);
                return;
            case SHOWING:
            default:
                button.setText(text);
                button.setOnClickListener(onClick);
                button.requestFocus();
        }
    }

    /**
     * Temporarily conceals the button and schedules it to reappear - the
     * pending match is preserved, NOT discarded. This is a deliberate
     * behavior change from the original implementation, where hide() tore
     * the overlay down for good; a future reader must not assume that's
     * still true. See abandon() for the permanent teardown this used to do.
     * Idempotent: a no-op unless currently SHOWING.
     *
     * Gated behind Preferences.isOverlayReappearEnabled() (Settings row,
     * default on, added 2026-08-25) - with it off, this reverts to the
     * original permanent-dismiss-on-keypress behavior by calling abandon()
     * directly instead of concealing/rescheduling.
     */
    void hide() {
        if (state != State.SHOWING) {
            return;
        }
        if (!Preferences.isOverlayReappearEnabled(appContext)) {
            abandon();
            return;
        }
        conceal();
        scheduleReappear();
    }

    /**
     * Conceals the button while the ambiguous-match chooser
     * (MatchTrayOverlay) takes over, WITHOUT abandoning the pending match
     * and WITHOUT scheduling a reappear - the tray is about to hold the
     * screen, and the button popping back up over it would fight it for
     * focus. The Home receiver and lifetime cap stay armed, so Home /
     * lobby detection / the cap can still permanently abandon as usual
     * while the tray is up. The reappear cycle restarts only if the tray
     * is dismissed without a pick - see resumeAfterHandoff(); a real pick
     * abandons instead (TvRelayAccessibilityService's tray listener).
     *
     * With the reappear setting off (Preferences.isOverlayReappearEnabled),
     * this still abandons outright - that setting's contract is
     * "dismissals are permanent, like the original behavior," and a
     * handoff tap counts.
     */
    private void concealForHandoff() {
        if (!Preferences.isOverlayReappearEnabled(appContext)) {
            abandon();
            return;
        }
        cancelCycleTimer();
        conceal();
    }

    /**
     * Called (via TvRelayAccessibilityService's tray listener) when the
     * chooser tray is dismissed WITHOUT picking a candidate (BACK/cancel) -
     * resumes the normal concealed-then-reappear cycle for the still-pending
     * match, so the "Choose in {App}" button comes back after the usual
     * delay instead of vanishing forever. No-op unless a handoff-concealed
     * match is actually pending (it isn't when the chooser was shown
     * without the overlay permission, when the reappear setting is off -
     * concealForHandoff() abandoned instead - or when the lifetime cap
     * expired while the tray was up).
     */
    void resumeAfterHandoff() {
        if (state != State.HIDDEN) {
            return;
        }
        scheduleReappear();
    }

    /**
     * Permanently discards the pending match: cancels all timers,
     * unregisters the Home receiver, removes the overlay window, and resets
     * to IDLE. This is what hide() used to do unconditionally; now it's
     * only reached via a real abandonment signal (see the class javadoc).
     */
    void abandon() {
        cancelCycleTimer();
        cancelLifetimeCap();
        if (homeReceiver != null) {
            try {
                appContext.unregisterReceiver(homeReceiver);
            } catch (Exception e) {
                // Already unregistered - nothing to do.
            }
            homeReceiver = null;
        }
        if (button != null) {
            try {
                windowManager.removeView(button);
            } catch (Exception e) {
                Log.e(TAG, "Could not remove Watch Now overlay", e);
            }
        }
        button = null;
        params = null;
        state = State.IDLE;
    }

    /**
     * Cheap check for TvRelayAccessibilityService to poll before bothering
     * to process a window-state-changed event at all - see
     * handleWindowStateChanged() there.
     */
    boolean hasPendingMatch() {
        return state != State.IDLE;
    }

    /**
     * Forces a real compositor frame by briefly toggling this window's own
     * visibility, then restoring it - used by OcrCaptureManager right before
     * requesting a screen capture. No-op if no window is currently up
     * (button == null - e.g. OCR was triggered without the "display over
     * other apps" permission this overlay needs, or no match happens to be
     * pending at all right now).
     *
     * Grounded in confirmed on-device behavior (2026-08-25), not a guess:
     * MediaProjection's mirrored VirtualDisplay can stop producing new
     * frames entirely once the screen is fully static, and a real capture
     * sat with no result for 16+ seconds, resolving the instant the user
     * happened to press a key - which runs conceal()/reveal() below,
     * toggling this exact button between VISIBLE and INVISIBLE. That was
     * the only thing ever observed to unstick a hung capture, so this
     * reproduces the same toggle deliberately instead of waiting on the
     * user to do it by chance. See CAPTURE_TIMEOUT_MS's javadoc in
     * OcrCaptureForegroundService for the full investigation, and
     * OcrCaptureManager.captureAndRecognize() for where this is called -
     * specifically AFTER binder.captureFrame() has already armed its
     * pending callback, not before: imageAvailableListener drains and
     * closes every frame the instant it arrives regardless of whether a
     * capture is pending, so a frame produced too early (e.g. while still
     * waiting out the settle delay) gets silently thrown away before ever
     * being requested - confirmed the actual reason the earlier
     * "grab whatever's already buffered" fix rarely found anything.
     */
    void nudgeForFreshFrame() {
        if (button == null || params == null) {
            return;
        }
        try {
            int originalVisibility = button.getVisibility();
            int toggled = originalVisibility == View.VISIBLE ? View.INVISIBLE : View.VISIBLE;
            button.setVisibility(toggled);
            windowManager.updateViewLayout(button, params);
            button.setVisibility(originalVisibility);
            windowManager.updateViewLayout(button, params);
        } catch (Exception e) {
            Log.w(TAG, "Could not nudge overlay for a fresh frame", e);
        }
    }

    /**
     * Called by TvRelayAccessibilityService when it detects the user is
     * back at the launcher home/lobby screen - abandons whatever match is
     * pending, since there's no longer a "detail page" context for it to
     * make sense in.
     */
    void notifyLauncherLobby() {
        if (state == State.IDLE) {
            return;
        }
        Log.d(TAG, "Abandoning pending match: launcher lobby detected");
        abandon();
    }

    private void ensureButton() {
        if (button != null) {
            return;
        }
        if (!isPermissionGranted()) {
            return;
        }

        Button newButton = new Button(appContext);
        // Deliberately not FLAG_NOT_FOCUSABLE: the button needs to be able
        // to take D-pad focus to be usable at all on a TV. That means it
        // becomes the focused window the moment it's shown, which is
        // exactly why Back needs explicit handling here - without it
        // there would be no way to dismiss the overlay and hand control
        // back to whatever's underneath.
        newButton.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                hide();
                return true;
            }
            // A D-pad press means the user is trying to navigate, not
            // confirm - get out of the way immediately instead of eating
            // the input. This button is the only focusable view in its
            // window, so without this the press did nothing at all
            // (confirmed on-device: user stuck unable to move at all - and
            // since there's no time-based auto-dismiss any more, see the
            // class javadoc, nothing would ever rescue them). React on
            // ACTION_DOWN so the very first press dismisses it, not just the
            // eventual key-up.
            // KEYCODE_DPAD_CENTER/ENTER are deliberately excluded - those
            // are the confirm click itself and must reach the button.
            if (isDirectionalKey(keyCode) && event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() == 0) {
                Log.d(TAG, "Dismissing overlay: directional key " + keyCode);
                hide();
                return true;
            }
            return false;
        });

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.END;
        params.x = 48;
        params.y = 48;

        try {
            windowManager.addView(newButton, params);
            button = newButton;
            state = State.SHOWING;
            registerHomeReceiver();
            startLifetimeCap();
        } catch (Exception e) {
            Log.e(TAG, "Could not show Watch Now overlay", e);
        }
    }

    /**
     * Reveals a concealed button back to a normal, focusable, touchable
     * state. No-op unless currently HIDDEN.
     */
    private void reveal() {
        if (state != State.HIDDEN || button == null) {
            return;
        }
        params.flags &= ~(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        button.setVisibility(View.VISIBLE);
        windowManager.updateViewLayout(button, params);
        button.requestFocus();
        state = State.SHOWING;
    }

    /**
     * Conceals a showing button without destroying it - the window and its
     * pending match survive, just invisible and unable to steal focus or
     * touches. Uses INVISIBLE rather than GONE to keep this
     * WindowManager-hosted view's WRAP_CONTENT measurement stable rather
     * than risking odd re-measurement on the next reveal(). FLAG_NOT_TOUCHABLE
     * is added alongside FLAG_NOT_FOCUSABLE as belt-and-suspenders so the
     * invisible window can't intercept even a stray touch while concealed -
     * FLAG_NOT_TOUCH_MODAL (existing flag, kept) is unrelated: that one is
     * about letting touches outside the window pass through even when the
     * window IS touchable/focusable. No-op unless currently SHOWING.
     */
    private void conceal() {
        if (state != State.SHOWING || button == null) {
            return;
        }
        params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        button.setVisibility(View.INVISIBLE);
        windowManager.updateViewLayout(button, params);
        state = State.HIDDEN;
    }

    private void scheduleReappear() {
        cancelCycleTimer();
        cycleTimer = this::reveal;
        mainHandler.postDelayed(cycleTimer, REAPPEAR_DELAY_MS);
    }

    private void cancelCycleTimer() {
        if (cycleTimer != null) {
            mainHandler.removeCallbacks(cycleTimer);
            cycleTimer = null;
        }
    }

    /**
     * Started once, only from ensureButton()'s success path - deliberately
     * NOT reset by conceal()/reveal() cycles, since it bounds total
     * pending-match lifetime, not per-visible-segment lifetime.
     */
    private void startLifetimeCap() {
        lifetimeCapTimer = this::onLifetimeCapExpired;
        mainHandler.postDelayed(lifetimeCapTimer, MAX_LIFETIME_MS);
    }

    private void cancelLifetimeCap() {
        if (lifetimeCapTimer != null) {
            mainHandler.removeCallbacks(lifetimeCapTimer);
            lifetimeCapTimer = null;
        }
    }

    private void onLifetimeCapExpired() {
        Log.w(TAG, "Abandoning Watch Now overlay: hit the " + MAX_LIFETIME_MS
                + "ms absolute lifetime cap without ever detecting a return to the launcher lobby - "
                + "likely means LOBBY_CLASS_MARKER (TvRelayAccessibilityService) needs correcting, "
                + "or the user genuinely browsed this long");
        abandon();
    }

    private static boolean isDirectionalKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    /**
     * Pressing Home doesn't deliver a normal KEYCODE_HOME to app windows -
     * the system intercepts it before any app sees it, so it can't be
     * caught in the button's OnKeyListener above. This overlay is a
     * standalone system window (TYPE_APPLICATION_OVERLAY/TYPE_PHONE), not
     * tied to any Activity, so without this it kept floating on top of the
     * Home screen after Home was pressed - and since it still held key
     * focus and there's no time-based auto-dismiss (see the class javadoc),
     * the user couldn't move the D-pad at all, ever, without this. Confirmed
     * real bug on-device.
     *
     * ACTION_CLOSE_SYSTEM_DIALOGS is the standard (if informal) signal
     * other overlay apps use to detect this - it's broadcast by the system
     * on Home/Recents presses among other things. Only *sending* it is
     * restricted on API 31+; apps can still register to receive it.
     *
     * Home reliably means "at the lobby," so this calls abandon() (not
     * hide()) - a permanent-abandon signal, not just a temporary dismiss.
     */
    private void registerHomeReceiver() {
        if (homeReceiver != null) {
            return;
        }
        homeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(TAG, "Dismissing overlay: ACTION_CLOSE_SYSTEM_DIALOGS ("
                        + intent.getStringExtra("reason") + ")");
                abandon();
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(homeReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            appContext.registerReceiver(homeReceiver, filter);
        }
    }
}
