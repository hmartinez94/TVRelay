package com.hmartinez94.tvrelay;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * Watches for clicks on the Google TV launcher
 * (com.google.android.apps.tv.launcherx) and the Fire TV launcher
 * (com.amazon.tv.launcher). When the clicked item is a movie/show
 * recommendation card, extracts its title, and opens it in the app chosen
 * in Settings instead of whatever the launcher would normally do. For
 * Nuvio/Stremio that means resolving the title via TMDB or TheTVDB first
 * (see Preferences.getMetadataProvider) to get an IMDb/TMDB id for a
 * direct content deep link; Plex/Jellyfin skip resolution entirely and
 * just hand the title to the player's own search screen (see PlayerApp's
 * class doc, PlayerLauncher.prepareTitleSearch()).
 *
 * Every other click is left alone - normal launcher behavior (app icons,
 * the "Your apps" row, etc.) is untouched.
 */
public class TvRelayAccessibilityService extends AccessibilityService {

    private static final String TAG = "TvRelayService";

    private static final String GOOGLE_TV_LAUNCHER_PACKAGE = "com.google.android.apps.tv.launcherx";
    private static final String AMAZON_LAUNCHER_PACKAGE = "com.amazon.tv.launcher";

    // Markers that identify a card as a real movie/show recommendation
    // (as opposed to an app icon or other launcher chrome) - used only for
    // DETECTION now, not for extracting the title. Confirmed against real
    // English-locale content-desc strings on-device:
    //   "Scary Movie, costs: $9.99, original price: $19.99, rotten rating: 23% on Rotten Tomatoes"
    //   "REACHER, requires Prime Video subscription, fresh rating: 95% on Rotten Tomatoes"
    //   "Fountain of Youth, Apple TV, rotten rating: 35% on Rotten Tomatoes"
    // The Spanish strings match the reference app this is based on.
    private static final String[] TITLE_MARKERS = {
            "cuesta:", "se necesita una suscripción a", "puntuación:",
            "costs:", "rating:"
    };

    // "requires {Provider} subscription" - a variable provider name sits
    // between fixed words, so this can't be a plain TITLE_MARKERS entry.
    // (First guessed as the fixed string "subscription required for",
    // which turned out to be the wrong wording entirely - confirmed via
    // real content-desc strings like "requires Prime Video subscription".)
    private static final Pattern SUBSCRIPTION_MARKER = Pattern.compile("requires .+ subscription");

    // Distinguishes a YouTube video recommendation card from a movie/show
    // card, so it can be redirected to a sideloaded YouTube TV client
    // instead - SmartTube or TizenTube Cobalt, whichever is installed (see
    // PlayerLauncher.prepareYouTubeRedirect()) - rather than being treated
    // as a movie/show search - which, left unchecked, is exactly what the
    // generic "{Title}, {rest}" fallback in isMovieOrShowCard() would
    // otherwise do to it.
    //
    // Originally a blind guess (mirrored Bananz0/OpenTVBridge's own marker
    // choice, 2026-08-24) never confirmed against a real click. CONFIRMED
    // WRONG 2026-08-25 via a real captured YouTube-video click ("The BEST
    // Crispy Sautéed Potatoes", a cooking video): the real content-desc is
    // JUST the bare title, no duration marker anywhere, no comma, nothing
    // else - so this array never matched, and (since isMovieOrShowCard()
    // separately requires a comma to match anything) the click was simply
    // dropped, unhandled. Kept as a still-plausible marker for a locale/
    // launcher-build variant that DOES include one - checked first, before
    // the real fallback below - but isYouTubeCard() no longer depends on
    // it alone.
    private static final String[] YOUTUBE_MARKERS = {"duración:", "duration:"};

    private static final String FIRE_TV_MAIN_IMAGE_ID = "com.amazon.tv.launcher:id/main_image";

    // Identifies a TYPE_WINDOW_STATE_CHANGED event's window as the Google TV
    // launcher's own home/lobby screen, so a pending WatchNowOverlay match
    // can be abandoned once the user actually navigates back there - see
    // handleLauncherLobbyReturn() and WatchNowOverlay.notifyLauncherLobby().
    //
    // UNVERIFIED against a real event.getClassName() value for this exact
    // flow (a recommendation-card click, this feature's actual trigger) -
    // the only evidence for "HomeActivity" comes from a prior,
    // since-reverted investigation (documented in this project's CLAUDE.md,
    // not in this file) that temporarily widened eventTypes/packageNames to
    // diagnose an unrelated voice-search bug, and captured a real logcat
    // transition "HomeActivity -> katniss (FrameLayout) -> systemui ->
    // launcherx EntityActivity" for a VOICE-SEARCH-driven detail page. That
    // TYPE_WINDOW_STATE_CHANGED event carried text=[Detail Page] (a static,
    // generic label - useless as a title) and contentDesc=[null], but only
    // the SHORT class names "HomeActivity"/"EntityActivity" were visible in
    // that trace - the fully-qualified class name was never independently
    // confirmed against event.getClassName() in this codebase. That's why
    // the match below uses contains() rather than equals()/contentEquals() -
    // deliberately forgiving of an unconfirmed outer-class/package prefix,
    // same pattern as isYouTubeCard()/isMovieOrShowCard()'s marker matching.
    // Also unverified: whether this substring could ever mis-match against
    // "EntityActivity" (glimpsed in the same trace) or some other window
    // class name that happens to contain "HomeActivity".
    //
    // Fix-if-wrong workflow: handleLauncherLobbyReturn() below logs every
    // window-state class name seen while a match is pending, unconditionally
    // - the same workflow already used to confirm/correct YOUTUBE_MARKERS.
    // WatchNowOverlay's MAX_LIFETIME_MS cap firing (its own Log.w) is itself
    // indirect diagnostic evidence this constant is wrong and never matched.
    private static final String LOBBY_CLASS_MARKER = "HomeActivity";

    // Avoids re-handling the same title repeatedly.
    private static final long DEBOUNCE_MS = 4000;
    private volatile String lastHandledTitle;
    private volatile long lastHandledAtMillis;

    // Separate, time-only debounce for the OCR fallback trigger points
    // (see triggerOcrCapture()) - there's no title yet at trigger time, so
    // the title-based debounce above doesn't apply.
    private static final long OCR_DEBOUNCE_MS = 3000;
    private volatile long lastOcrTriggerAtMillis;

    // Cooldown after ANY of our own overlay transitions (WatchNowOverlay
    // showing/hiding/abandoning, MatchTrayOverlay opening/closing) before a
    // window-state event is trusted as a fresh voice-search landing - see
    // handleVoiceSearchDetailPage()'s javadoc. Confirmed real bug this
    // fixes (2026-08-25, on-device): overlay.hasPendingMatch() alone isn't
    // enough - pressing OK on an ambiguous match hands off to
    // MatchTrayOverlay (abandoning WatchNowOverlay first), and dismissing
    // THAT tray (Back) hands focus back to the launcher's EntityActivity,
    // producing another lookalike window-state event with nothing pending
    // on either overlay by then. (2026-08-26 update: with the reappear
    // setting on, that handoff no longer abandons WatchNowOverlay - see
    // its concealForHandoff() - so hasPendingMatch() now also covers that
    // specific sequence; this cooldown still covers the reappear-off path,
    // where the handoff does still abandon, plus every other transition.)
    // Every UI transition either overlay makes
    // is a plausible cause of a spurious focus-change event, not just the
    // ones this project happened to catch on-device first - so this is
    // marked broadly (see markOwnOverlayActivity()'s call sites) rather
    // than trying to enumerate exactly which transitions can trigger it.
    private static final long OVERLAY_TRANSITION_COOLDOWN_MS = 2000;
    private volatile long lastOwnOverlayActivityAtMillis;

    private void markOwnOverlayActivity() {
        lastOwnOverlayActivityAtMillis = System.currentTimeMillis();
    }

    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WatchNowOverlay overlay;
    private MatchTrayOverlay trayOverlay;
    private OcrCaptureManager ocrCaptureManager;
    private SharedPreferences.OnSharedPreferenceChangeListener ocrPrefsListener;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        // Records the "actually bound, for real" signal used by
        // SettingsStepFragment's Restricted-settings heuristic - see
        // Preferences.hasAccessibilityServiceEverConnected().
        Preferences.setAccessibilityServiceEverConnected(this, true);

        overlay = new WatchNowOverlay(this);

        // Start from the info the system parsed from the XML meta-data
        // rather than a brand-new AccessibilityServiceInfo, to be safe.
        // Still set programmatically, not left to the XML alone: on some
        // devices (TCL/Android 12, per the reference app this is based on)
        // the XML config silently fails to apply at runtime - dumpsys
        // accessibility shows an empty eventTypes despite a correctly
        // compiled XML.
        //
        // Deliberately click-event-only for movie/show detection (see
        // isMovieOrShowCard()) - window-content introspection
        // (getRootInActiveWindow()/event.getSource()/getWindows(), even
        // with every relevant flag set) was tested extensively on a real
        // device and consistently returned null/empty, most likely because
        // Android restricts that capability for sideloaded apps that
        // haven't completed Google's Play Console AccessibilityService
        // declaration (see README/plan) - not something fixable from here.
        // takeScreenshot() was also tried as an alternative, on the theory
        // that it's gated by a separate capability (canTakeScreenshot,
        // independent of canRetrieveWindowContent) and operates on pixels
        // rather than the accessibility tree - it threw a hard
        // SecurityException ("Services don't have the capability of taking
        // the screenshot") on the same real device, confirming the
        // restriction is broad (any sensitive capability), not narrow to
        // one specific API. Cards with no accessible text at all on the
        // click event (seen specifically on the "Top picks for you" ML row)
        // instead fall back to the OCR path below (triggerOcrCapture()) when
        // that optional fallback is enabled.
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) {
            info = new AccessibilityServiceInfo();
        }
        // TYPE_WINDOW_STATE_CHANGED is included conditionally - see
        // currentEventTypes(): needed while either the OCR fallback is
        // enabled (to catch the voice-search EntityActivity transition) or
        // a WatchNowOverlay match is currently pending (to detect
        // return-to-lobby). info.packageNames below stays UNCHANGED for
        // this purpose - do not widen it to request window-state events for
        // AMAZON_LAUNCHER_PACKAGE too; Fire TV is documented (see class
        // comments already in this file, and the project's CLAUDE.md) to
        // receive zero AccessibilityEvents of any type on real hardware, so
        // there's no point.
        info.eventTypes = currentEventTypes();
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        info.packageNames = new String[]{GOOGLE_TV_LAUNCHER_PACKAGE, AMAZON_LAUNCHER_PACKAGE};
        // Required for getViewIdResourceName() to return anything - the
        // Fire TV card lookup below depends on it.
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        setServiceInfo(info);

        ocrCaptureManager = new OcrCaptureManager(this, overlay);

        // Keeps TYPE_WINDOW_STATE_CHANGED live-conditional on preference
        // changes (the OCR toggle) - see currentEventTypes()/
        // refreshEventTypes(). The OTHER half of the union - whether a
        // WatchNowOverlay match is pending - is instead recomputed
        // reactively at the call sites below that change overlay state,
        // since WatchNowOverlay has no preference-style change notification
        // of its own.
        ocrPrefsListener = (sharedPreferences, key) -> refreshEventTypes();
        Preferences.rawPrefs(this).registerOnSharedPreferenceChangeListener(ocrPrefsListener);
    }

    /**
     * Base click-only eventTypes, plus TYPE_WINDOW_STATE_CHANGED whenever
     * EITHER of two independent features needs it: the OCR fallback's
     * voice-search trigger (Preferences.isOcrFallbackEnabled()), or
     * WatchNowOverlay's lobby-return detection (overlay.hasPendingMatch()).
     * refreshEventTypes()'s call sites below keep this in sync with
     * whichever condition changed most recently, without either feature
     * needing to know about the other.
     */
    private int currentEventTypes() {
        int eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED;
        boolean needsWindowState = Preferences.isOcrFallbackEnabled(this)
                || (overlay != null && overlay.hasPendingMatch());
        if (needsWindowState) {
            eventTypes |= AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        }
        return eventTypes;
    }

    /**
     * Reapplies currentEventTypes() to the live AccessibilityServiceInfo.
     * Called from the OCR preference listener (onServiceConnected) and from
     * the call sites below that change whether a WatchNowOverlay match is
     * pending (showLoading() and the abandon() sites) - NOT from inside
     * WatchNowOverlay itself when it autonomously abandons a match (e.g. its
     * own lobby-detection or MAX_LIFETIME_MS cap), since it has no reference
     * back to this service. That's a deliberate, harmless gap: it just means
     * TYPE_WINDOW_STATE_CHANGED may stay armed a little longer than
     * strictly necessary in that case, narrowing back down on the next
     * recompute (e.g. the next click) rather than instantly - never a
     * correctness problem, since handleLauncherLobbyReturn() is a no-op once
     * hasPendingMatch() is actually false.
     */
    private void refreshEventTypes() {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) {
            return;
        }
        info.eventTypes = currentEventTypes();
        setServiceInfo(info);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handleWindowStateChanged(event);
            return;
        }
        if (event.getEventType() != AccessibilityEvent.TYPE_VIEW_CLICKED) {
            return;
        }

        if (!Preferences.isDisclosureAccepted(this)) {
            // Play policy requires explicit consent before this service
            // acts; also a sane default outside Play - sideloaded users
            // still have to get past the disclosure screen on first run.
            return;
        }

        // Unconditional diagnostic log: needed because every branch below
        // returns silently when a click doesn't match the expected card
        // format - without this there is no way to see what a real card's
        // event actually looks like on a given device/locale/screen. Remove
        // once title extraction is confirmed working across real devices.
        AccessibilityNodeInfo diagSource = event.getSource();
        Log.d(TAG, "Click event: pkg=" + event.getPackageName()
                + " class=" + event.getClassName()
                + " contentDesc=[" + event.getContentDescription() + "]"
                + " text=" + event.getText()
                + " srcViewId=" + (diagSource != null ? diagSource.getViewIdResourceName() : null));

        CharSequence packageName = event.getPackageName();
        if (packageName != null && AMAZON_LAUNCHER_PACKAGE.contentEquals(packageName)) {
            String title = extractFireTvTitle(event);
            if (title != null && !title.trim().isEmpty()) {
                Log.d(TAG, "Movie/show detected (Fire TV): " + title);
                handleMovieClick(title);
            }
            return;
        }

        // The row card's content-desc travels on event.getContentDescription(),
        // NOT event.getSource().getContentDescription() (always null for
        // these cards).
        CharSequence descCharSeq = event.getContentDescription();
        String desc = descCharSeq != null ? descCharSeq.toString() : null;
        if (desc != null && !desc.trim().isEmpty()) {
            if (isYouTubeCard(event, desc)) {
                String title = extractTitle(desc);
                if (!title.isEmpty()) {
                    Log.d(TAG, "YouTube video detected: " + title);
                    handleYouTubeClick(title);
                }
                return;
            }
            if (isMovieOrShowCard(event, desc)) {
                String title = extractTitle(desc);
                if (!title.isEmpty()) {
                    Log.d(TAG, "Movie/show detected: " + title);
                    handleMovieClick(title);
                }
            }
            return;
        }

        // Big autoplay hero banner (top row of Home): title travels in
        // event.getText(), not contentDescription. Format:
        // [Title, subtitle, synopsis, CTA]. Sponsored entries lead with
        // "Sponsored"/"Patrocinado" and are ignored - not real recommendations.
        String heroTitle = extractHeroTitle(event);
        if (heroTitle != null) {
            Log.d(TAG, "Movie/show detected (hero banner): " + heroTitle);
            handleMovieClick(heroTitle);
            return;
        }

        // Some rows populate content-desc a moment after the click - it's
        // still empty at event time. Retry once, shortly after, by
        // re-reading the node.
        AccessibilityNodeInfo source = event.getSource();
        if (source == null) {
            // Genuinely empty payload - no contentDescription, no hero
            // text, no source node to retry against. This is the
            // "Top picks for you" case from CLAUDE.md's "capabilities
            // wall" - fall back to the OCR path instead of giving up.
            triggerOcrCapture();
            return;
        }
        mainHandler.postDelayed(() -> {
            source.refresh();
            CharSequence delayed = source.getContentDescription();
            String delayedDesc = delayed != null ? delayed.toString() : null;
            if (delayedDesc == null || delayedDesc.trim().isEmpty()) {
                // Still empty after the delayed retry - same fallback as
                // the immediate source==null case above.
                triggerOcrCapture();
                return;
            }
            if (isYouTubeCard(event, delayedDesc)) {
                String title = extractTitle(delayedDesc);
                if (!title.isEmpty()) {
                    Log.d(TAG, "YouTube video detected (delayed): " + title);
                    handleYouTubeClick(title);
                }
                return;
            }
            if (isMovieOrShowCard(event, delayedDesc)) {
                String title = extractTitle(delayedDesc);
                if (!title.isEmpty()) {
                    Log.d(TAG, "Movie/show detected (delayed): " + title);
                    handleMovieClick(title);
                }
            }
        }, 600);
    }

    /**
     * Combined TYPE_WINDOW_STATE_CHANGED handler for the two independent
     * features that need it - see currentEventTypes()'s javadoc. Each half
     * below is a self-contained, independently-gated check; they can't
     * interfere with each other since they key off different conditions
     * (OCR fallback enabled vs. an overlay match pending) and different
     * outcomes (triggering an OCR capture vs. abandoning a pending overlay
     * match). Only reachable at all while TYPE_WINDOW_STATE_CHANGED is in
     * the service's eventTypes - see currentEventTypes().
     */
    private void handleWindowStateChanged(AccessibilityEvent event) {
        handleVoiceSearchDetailPage(event);
        handleLauncherLobbyReturn(event);
    }

    /**
     * The voice-search trigger point - see CLAUDE.md's "voice search wall".
     * Speaking a title into the launcher's mic never fires a click event at
     * all (the flow goes HomeActivity -> katniss -> systemui -> launcherx
     * EntityActivity with zero TYPE_VIEW_CLICKED events anywhere), so this
     * watches for the one confirmed, real signal instead: the window-state
     * transition into EntityActivity, whose event.getText() carries only
     * the generic, locale-static label "Detail Page" - never the real
     * title.
     *
     * CORRECTION (2026-08-25, confirmed via real on-device logcat): this
     * EntityActivity/"Detail Page" signature is NOT unique to voice search
     * after all - the original "voice search wall" investigation only ever
     * captured it during an actual voice-search flow, and never checked
     * whether a normal card click's own follow-on navigation produces the
     * same signature. It does: Google TV's launcher navigates to its own
     * EntityActivity detail page after basically any click, as its default
     * behavior, independent of whatever TVRelay does with that same click -
     * TVRelay observes the click event, it doesn't cancel the launcher's
     * own reaction to it. Without a guard, that meant every ordinary,
     * already-correctly-detected movie click ALSO tripped this "voice
     * search" path a moment later, which called overlay.showLoading() again
     * and silently abandoned the real pending match (see showLoading()'s
     * "fresh click supersedes whatever was pending" behavior in
     * WatchNowOverlay) - then the OCR capture (of a screen that isn't a
     * title-less voice-search landing at all) typically fails to find
     * anything useful and abandons for good, so the button vanished for
     * every movie, not just genuinely undetectable ones.
     *
     * SECOND CORRECTION (2026-08-25, confirmed via real on-device logcat): a
     * time-window guard (skip if a click was handled within the last
     * DEBOUNCE_MS) is not enough either - it stops the immediate aftermath
     * of a normal click, but a real, worse cascade shows up later: once a
     * resolved match is shown, WatchNowOverlay.hide() used to fire
     * automatically after AUTO_DISMISS_MS (10s) of inactivity, which flipped
     * FLAG_NOT_FOCUSABLE on the overlay window to conceal it - and THAT
     * focus change back to the launcher's EntityActivity window itself
     * generated another TYPE_WINDOW_STATE_CHANGED/"Detail Page" event, well
     * past any short time-window guard. Confirmed on-device: this created a
     * self-sustaining loop (OCR re-triggers -> re-shows the same confirm
     * button -> resets the 10s idle timer -> hide() fires again -> repeat
     * indefinitely) that only stopped when the user navigated away.
     * AUTO_DISMISS_MS was removed entirely later the same day (see
     * WatchNowOverlay's class javadoc) specifically because hide() should
     * only ever be a direct response to a key press, which closes off this
     * exact loop at the source - but the cooldown built below is kept as-is
     * regardless, since it also guards the THIRD CORRECTION's cascade and
     * ordinary click-triggered EntityActivity navigation, neither of which
     * this removal touches.
     *
     * THIRD CORRECTION (2026-08-25, confirmed via real on-device testing):
     * checking WatchNowOverlay's state alone still isn't enough - pressing
     * OK on an ambiguous match hands off to MatchTrayOverlay (abandoning
     * WatchNowOverlay first, so hasPendingMatch() goes back to false), and
     * dismissing THAT tray (Back) hands focus back to the launcher's
     * EntityActivity, producing yet another lookalike window-state event
     * with nothing pending on either overlay by then. (2026-08-26: the
     * handoff described in that parenthetical has since changed - with the
     * reappear setting on it now CONCEALS WatchNowOverlay instead of
     * abandoning it, see concealForHandoff(), so hasPendingMatch() stays
     * true through that exact sequence - but the correction's conclusion
     * stands unchanged, because the reappear-off path still abandons and
     * the general point below was never specific to that one sequence.)
     * There is no finite
     * enumeration of "which overlay transition might cause this" that's
     * likely to be complete - every show/hide/abandon on either overlay is
     * a plausible cause, since all of them change what currently holds
     * window focus.
     *
     * Fix: instead of tracking state per-overlay, track a single cooldown
     * (lastOwnOverlayActivityAtMillis / OVERLAY_TRANSITION_COOLDOWN_MS)
     * bumped by markOwnOverlayActivity() at every place either overlay's
     * visibility changes for any reason - see that method's call sites.
     * Combined with the two still-useful direct state checks (an overlay
     * transition system this reactive will almost always have very recently
     * bumped the cooldown anyway, but the direct checks cost nothing and
     * cover the rare case a transition's own bump hasn't landed yet).
     */
    private void handleVoiceSearchDetailPage(AccessibilityEvent event) {
        if (!Preferences.isDisclosureAccepted(this) || !Preferences.isOcrFallbackEnabled(this)) {
            return;
        }
        if (overlay != null && overlay.hasPendingMatch()) {
            return;
        }
        if (trayOverlay != null && trayOverlay.isShowing()) {
            return;
        }
        if ((System.currentTimeMillis() - lastOwnOverlayActivityAtMillis) < OVERLAY_TRANSITION_COOLDOWN_MS) {
            // Something on either overlay changed very recently - this
            // transition is almost certainly a side effect of that, not a
            // title-less voice-search landing. See the class doc above.
            return;
        }
        CharSequence className = event.getClassName();
        if (className == null || !className.toString().contains("EntityActivity")) {
            return;
        }
        List<CharSequence> text = event.getText();
        if (text == null || text.isEmpty() || !"Detail Page".contentEquals(text.get(0))) {
            return;
        }
        Log.d(TAG, "Voice-search detail page detected with no title - triggering OCR fallback");
        triggerOcrCapture();
    }

    /**
     * Watches for the user navigating back to the Google TV launcher's
     * home/lobby screen while a WatchNowOverlay match is still pending, so
     * that match can be abandoned for good instead of endlessly reappearing.
     * See LOBBY_CLASS_MARKER's javadoc for exactly how confirmed/unconfirmed
     * this heuristic currently is.
     */
    private void handleLauncherLobbyReturn(AccessibilityEvent event) {
        if (overlay == null || !overlay.hasPendingMatch()) {
            return;
        }
        CharSequence packageName = event.getPackageName();
        if (packageName == null || !GOOGLE_TV_LAUNCHER_PACKAGE.contentEquals(packageName)) {
            return;
        }

        // Unconditional (while a match is pending) diagnostic log - mirrors
        // the click-event diagnostic log above; needed to confirm or correct
        // LOBBY_CLASS_MARKER against real captured class names. Cheap: only
        // runs while hasPendingMatch() is true, i.e. rarely, not on every
        // launcher window transition in normal use.
        Log.d(TAG, "Window-state event while match pending: class=" + event.getClassName()
                + " text=" + event.getText() + " contentDesc=[" + event.getContentDescription() + "]");

        CharSequence className = event.getClassName();
        if (className != null && className.toString().contains(LOBBY_CLASS_MARKER)) {
            Log.d(TAG, "Detected return to launcher lobby (window class matched \""
                    + LOBBY_CLASS_MARKER + "\"): " + className);
            overlay.notifyLauncherLobby();
            refreshEventTypes();
        }
    }

    /**
     * Shared fallback trigger for both OCR scenarios: a movie/show click
     * whose AccessibilityEvent carries no readable title anywhere (see the
     * two triggerOcrCapture() call sites above, in the click-handling
     * path), and a voice-search result landing on EntityActivity with only
     * the generic "Detail Page" label (see handleVoiceSearchDetailPage()).
     * Neither case has a title yet - this captures the screen, extracts a
     * title via on-device OCR (OcrCaptureManager, itself gated behind its
     * own explicit consent screen - see Preferences.isOcrFallbackEnabled())
     * and feeds the result into the exact same resolve/confirm/launch
     * pipeline a normal click already uses (handleMovieClick()), rather
     * than building a second, parallel path.
     */
    private void triggerOcrCapture() {
        if (!Preferences.isOcrFallbackEnabled(this)) {
            return;
        }
        long now = System.currentTimeMillis();
        if ((now - lastOcrTriggerAtMillis) < OCR_DEBOUNCE_MS) {
            return;
        }
        lastOcrTriggerAtMillis = now;

        boolean confirmFirst = overlay != null && overlay.isPermissionGranted();
        if (confirmFirst) {
            overlay.showLoading();
            refreshEventTypes();
            markOwnOverlayActivity();
        }

        ocrCaptureManager.requestTitleCapture(new OcrCaptureManager.Callback() {
            @Override
            public void onTitleExtracted(String title) {
                Log.d(TAG, "OCR-detected title: " + title);
                handleMovieClick(title);
            }

            @Override
            public void onFailure(OcrCaptureManager.FailureReason reason) {
                Log.w(TAG, "OCR fallback failed: " + reason);
                if (confirmFirst) {
                    // abandon(), not hide(): nothing was ever resolved, so
                    // there's no pending match to offer back later - same
                    // reasoning as the resolution-failure paths in
                    // handleMovieClick() below.
                    overlay.abandon();
                    refreshEventTypes();
                    markOwnOverlayActivity();
                }
            }
        });
    }

    private String extractFireTvTitle(AccessibilityEvent event) {
        AccessibilityNodeInfo source = event.getSource();
        if (source == null) {
            return null;
        }
        return findFireTvMainImageDescription(source);
    }

    private String findFireTvMainImageDescription(AccessibilityNodeInfo node) {
        if (FIRE_TV_MAIN_IMAGE_ID.equals(node.getViewIdResourceName())) {
            CharSequence desc = node.getContentDescription();
            if (desc != null && !desc.toString().trim().isEmpty()) {
                return desc.toString();
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) {
                continue;
            }
            String result = findFireTvMainImageDescription(child);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private String extractHeroTitle(AccessibilityEvent event) {
        CharSequence className = event.getClassName();
        if (className == null || !"android.view.ViewGroup".contentEquals(className)) {
            return null;
        }
        List<CharSequence> parts = event.getText();
        if (parts == null || parts.isEmpty()) {
            return null;
        }
        CharSequence firstRaw = parts.get(0);
        if (firstRaw == null) {
            return null;
        }
        String first = firstRaw.toString().trim();
        if (first.isEmpty() || first.equalsIgnoreCase("Patrocinado") || first.equalsIgnoreCase("Sponsored")) {
            return null;
        }
        return first;
    }

    private boolean isYouTubeCard(AccessibilityEvent event, String contentDesc) {
        for (String marker : YOUTUBE_MARKERS) {
            if (contentDesc.contains(marker)) {
                return true;
            }
        }
        // Real fallback, confirmed 2026-08-25 - see YOUTUBE_MARKERS' javadoc.
        // A YouTube video card is the same android.view.View/empty-text
        // shape as a real movie/show card (see isMovieOrShowCard() below),
        // but with a BARE title and no comma-separated segment at all -
        // every real movie/show card seen so far always has at least one
        // (a price, rating, provider, or synopsis fragment after a comma).
        // Mutually exclusive with isMovieOrShowCard() by construction - a
        // comma-less content-desc could never have matched there anyway
        // (it requires one), so this can't misclassify a real movie/show
        // card regardless of which check runs first.
        CharSequence className = event.getClassName();
        if (className == null || !"android.view.View".contentEquals(className)) {
            return false;
        }
        List<CharSequence> text = event.getText();
        if (text != null && !text.isEmpty()) {
            return false;
        }
        return !contentDesc.contains(",");
    }

    private boolean isMovieOrShowCard(AccessibilityEvent event, String contentDesc) {
        for (String marker : TITLE_MARKERS) {
            if (contentDesc.contains(marker)) {
                return true;
            }
        }
        if (SUBSCRIPTION_MARKER.matcher(contentDesc).find()) {
            return true;
        }

        // Other formats with no price/rating marker:
        //  - Big "Google TV" posters for Movies/Shows: "{Title}, {synopsis}".
        //  - Free platforms, e.g. RTVE Play: "{Title}, RTVE Play".
        // Both are "{Title}, {rest}" on a real android.view.View card with
        // no text of its own. Banners/ads (e.g. "Netflix, Watch now") are
        // android.view.ViewGroup and DO have text ("WATCH NOW") - that's how
        // they're told apart without needing a list of known platforms.
        CharSequence className = event.getClassName();
        if (className == null || !"android.view.View".contentEquals(className)) {
            return false;
        }
        List<CharSequence> text = event.getText();
        if (text != null && !text.isEmpty()) {
            return false;
        }
        int commaIndex = contentDesc.indexOf(',');
        if (commaIndex <= 0) {
            return false;
        }
        return !contentDesc.substring(commaIndex + 1).trim().isEmpty();
    }

    private String extractTitle(String contentDesc) {
        // Once isMovieOrShowCard has confirmed this is a real card, the
        // title is always the text before the first comma - confirmed
        // against real content-desc strings, including ones with one or
        // more optional segments (provider name, subscription note) between
        // the title and the terminal price/rating marker, e.g.
        // "REACHER, requires Prime Video subscription, fresh rating: 95%..."
        // -> "REACHER". Earlier logic tried to split at the marker position
        // instead, which over-captured everything up to the marker
        // (including those intermediate segments) whenever more than one
        // existed. Trade-off: a title that itself contains a literal comma
        // would get truncated here - not seen in testing so far, and this
        // fixes clearly more real cases than it risks breaking.
        int commaIndex = contentDesc.indexOf(',');
        return (commaIndex >= 0 ? contentDesc.substring(0, commaIndex) : contentDesc).trim();
    }

    private void handleMovieClick(String title) {
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
            refreshEventTypes();
            markOwnOverlayActivity();
        }

        PlayerApp app = Preferences.getSelectedApp(this);
        if (app.usesTitleSearch()) {
            // Plex/Jellyfin have no content deep link (see PlayerApp's
            // class doc) - just hand the clicked title straight to their
            // own search screen. No MetadataResolver call at all: no
            // network, and nothing that can fail to "resolve" - see
            // PlayerLauncher.prepareTitleSearch().
            BooleanSupplier launch = PlayerLauncher.prepareTitleSearch(this, title);
            if (confirmFirst) {
                mainHandler.post(() -> overlay.showConfirmSearch(app, launch::getAsBoolean));
            } else {
                launch.getAsBoolean();
            }
            return;
        }

        backgroundExecutor.execute(() -> {
            List<TitleCandidate> candidates = MetadataResolver.resolveCandidates(this, title);
            if (candidates.isEmpty()) {
                Log.w(TAG, "Could not resolve an IMDB id for: " + title);
                if (confirmFirst) {
                    // abandon(), not hide(): resolution failed outright, so
                    // there's no pending match to ever offer back - under
                    // WatchNowOverlay's reappear semantics, hide() here would
                    // conceal-and-reschedule an inert "Loading..." button
                    // (no confirm listener was ever set) to keep popping back
                    // up for minutes until the lobby is detected or the
                    // lifetime cap expires, instead of just going away now.
                    mainHandler.post(() -> {
                        overlay.abandon();
                        refreshEventTypes();
                        markOwnOverlayActivity();
                    });
                }
                return;
            }

            if (Preferences.isChooserEnabled(this) && MetadataResolver.isAmbiguous(candidates)) {
                Log.d(TAG, "Ambiguous match for " + title + " (" + candidates.size() + " candidates)");
                if (confirmFirst) {
                    // Same confirm-before-launch step as an unambiguous
                    // match, just relabeled - the chooser only appears once
                    // the user presses it, exactly like a normal launch.
                    // (app already read above, outside this lambda.)
                    mainHandler.post(() -> overlay.showConfirmAmbiguous(app, () -> showChooser(title, candidates)));
                } else {
                    mainHandler.post(() -> showChooser(title, candidates));
                }
                return;
            }

            BooleanSupplier launch = PlayerLauncher.prepare(this, candidates.get(0));
            if (launch == null) {
                Log.w(TAG, "Could not resolve an IMDB id for: " + title);
                if (confirmFirst) {
                    // abandon(), not hide() - see the identical comment above.
                    mainHandler.post(() -> {
                        overlay.abandon();
                        refreshEventTypes();
                        markOwnOverlayActivity();
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

    /**
     * YouTube-redirect toggle for a YouTube video recommendation card - see
     * YOUTUBE_MARKERS and PlayerLauncher.prepareYouTubeRedirect(). Independent
     * of PlayerApp/handleMovieClick(): this fires (or doesn't) based only on
     * Preferences.isSmartTubeEnabled(), regardless of which movie/show
     * player is selected. No MetadataResolver call, same reasoning as the
     * title-search players in handleMovieClick() - just a YouTube search,
     * nothing to resolve.
     *
     * Targets SmartTube or TizenTube Cobalt (io.gh.reisxd.tizentube.cobalt -
     * a real, separate Android TV port of the well-known "TizenTube" ad-block
     * mod - see PlayerLauncher's TIZENTUBE_COBALT_* constants for the full
     * evidence and CLAUDE.md's "SmartTube redirect" section), per the user's
     * explicit Settings choice (Preferences.getYouTubeRedirectTarget(), a
     * dropdown - see PlayerLauncher.prepareYouTubeRedirect()), not
     * auto-detected install state. The preference name/method
     * (isSmartTubeEnabled) predates TizenTube Cobalt support and was kept
     * as-is rather than renamed - see Preferences.isSmartTubeEnabled()'s
     * javadoc - but the Settings strings shown to the user
     * (settings_smarttube_redirect / settings_smarttube_status_enabled) now
     * describe both targets, not just SmartTube, so the toggle's own
     * description doesn't lie about what it does.
     *
     * Unlike handleMovieClick(), this never shows the "Watch now"/confirm
     * overlay - launches straight to the target app's search, per explicit
     * user request (2026-08-25). A YouTube redirect is always just a search,
     * never a direct "open this exact thing" the way a resolved movie/show
     * match can be (see PlayerLauncher.prepareYouTubeRedirect()'s javadoc -
     * there's no video-id deep link attempted for either target), so it
     * doesn't carry the same "an accidental click silently opens the wrong
     * title" risk the confirm step exists to guard against for
     * handleMovieClick() - worst case here is an unwanted search opening,
     * not an unwanted title playing.
     */
    private void handleYouTubeClick(String title) {
        if (!Preferences.isSmartTubeEnabled(this)) {
            return;
        }

        long now = System.currentTimeMillis();
        if (title.equals(lastHandledTitle) && (now - lastHandledAtMillis) < DEBOUNCE_MS) {
            return;
        }
        lastHandledTitle = title;
        lastHandledAtMillis = now;

        PlayerLauncher.YouTubeRedirect redirect = PlayerLauncher.prepareYouTubeRedirect(this, title);
        redirect.launch.getAsBoolean();
    }

    /** Runs on the main thread - see the mainHandler.post() call sites above. */
    private void showChooser(String title, List<TitleCandidate> candidates) {
        if (trayOverlay == null) {
            trayOverlay = new MatchTrayOverlay(this, new MatchTrayOverlay.Listener() {
                @Override
                public void onDismissed() {
                    markOwnOverlayActivity();
                }

                @Override
                public void onCancelled() {
                    // Backing out of the chooser is a temporary dismissal,
                    // same as BACK on the confirm button itself: the match is
                    // still pending on the (handoff-concealed) WatchNowOverlay,
                    // so resume its normal reappear cycle - the
                    // "Choose in {App}" button comes back after the usual
                    // delay. Before this existed, tapping the ambiguous
                    // confirm abandoned the overlay outright, so BACK here
                    // stranded the user with no button, forever - confirmed
                    // real bug (2026-08-26), for both click- and OCR-detected
                    // titles. No-op when nothing is concealed (chooser shown
                    // without the overlay permission, or reappear setting
                    // off) - see WatchNowOverlay.resumeAfterHandoff().
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
                        refreshEventTypes();
                    }
                }
            });
        }
        trayOverlay.show(title, candidates);
        markOwnOverlayActivity();
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Service interrupted");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        backgroundExecutor.shutdown();
        if (overlay != null) {
            overlay.abandon();
        }
        if (trayOverlay != null) {
            trayOverlay.hide();
        }
        if (ocrPrefsListener != null) {
            Preferences.rawPrefs(this).unregisterOnSharedPreferenceChangeListener(ocrPrefsListener);
        }
        if (ocrCaptureManager != null) {
            ocrCaptureManager.shutdown();
        }
    }
}
