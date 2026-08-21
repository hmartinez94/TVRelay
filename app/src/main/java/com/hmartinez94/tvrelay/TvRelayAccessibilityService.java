package com.hmartinez94.tvrelay;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Watches for clicks on the Google TV launcher
 * (com.google.android.apps.tv.launcherx) and the Fire TV launcher
 * (com.amazon.tv.launcher). When the clicked item is a movie/show
 * recommendation card, extracts its title, resolves it to an IMDB id via
 * TheTVDB, and opens it in the app chosen in Settings (Nuvio, Stremio, or
 * Kodi) instead of whatever the launcher would normally do.
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

    private static final String FIRE_TV_MAIN_IMAGE_ID = "com.amazon.tv.launcher:id/main_image";

    // Avoids re-handling the same title repeatedly.
    private static final long DEBOUNCE_MS = 4000;
    private volatile String lastHandledTitle;
    private volatile long lastHandledAtMillis;

    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WatchNowOverlay overlay;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        overlay = new WatchNowOverlay(this);

        // Start from the info the system parsed from the XML meta-data
        // rather than a brand-new AccessibilityServiceInfo, to be safe.
        // Still set programmatically, not left to the XML alone: on some
        // devices (TCL/Android 12, per the reference app this is based on)
        // the XML config silently fails to apply at runtime - dumpsys
        // accessibility shows an empty eventTypes despite a correctly
        // compiled XML.
        //
        // Deliberately click-event-only: window-content introspection
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
        // one specific API. Detection is entirely event-payload-based as a
        // result: it only works for cards whose title is already present
        // on the click event itself (confirmed working: hero banner CTAs,
        // and content-desc-marker-based row cards - most row types on a
        // real Google TV launcher, per on-device testing). Cards with no
        // accessible text at all on the click event (seen specifically on
        // the "Top picks for you" ML-personalized row) cannot currently be
        // detected - see the "Search for a title manually" Settings entry.
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) {
            info = new AccessibilityServiceInfo();
        }
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        info.packageNames = new String[]{GOOGLE_TV_LAUNCHER_PACKAGE, AMAZON_LAUNCHER_PACKAGE};
        // Required for getViewIdResourceName() to return anything - the
        // Fire TV card lookup below depends on it.
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        setServiceInfo(info);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_VIEW_CLICKED) {
            return;
        }

        if (!Preferences.isDisclosureAccepted(this)) {
            // Play policy requires explicit consent before this service
            // acts; also a sane default outside Play - sideloaded users
            // still have to get past the disclosure screen on first run.
            return;
        }

        // Unconditional diagnostic log: the OS already filters delivery to
        // just the watched launcher packages (see onServiceConnected), so
        // this only fires for real launcher clicks, not app-wide noise.
        // Needed because every branch below returns silently when a click
        // doesn't match the expected card format - without this there is
        // no way to see what a real card's event actually looks like on a
        // given device/locale. Remove once title extraction is confirmed
        // working across real devices.
        Log.d(TAG, "Click event: pkg=" + event.getPackageName()
                + " class=" + event.getClassName()
                + " contentDesc=[" + event.getContentDescription() + "]"
                + " text=" + event.getText());

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
            return;
        }
        mainHandler.postDelayed(() -> {
            source.refresh();
            CharSequence delayed = source.getContentDescription();
            String delayedDesc = delayed != null ? delayed.toString() : null;
            if (delayedDesc != null && !delayedDesc.trim().isEmpty() && isMovieOrShowCard(event, delayedDesc)) {
                String title = extractTitle(delayedDesc);
                if (!title.isEmpty()) {
                    Log.d(TAG, "Movie/show detected (delayed): " + title);
                    handleMovieClick(title);
                }
            }
        }, 600);
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
        }

        backgroundExecutor.execute(() -> {
            TvdbMatch match = MetadataResolver.findImdbId(this, title);
            if (match == null) {
                Log.w(TAG, "Could not resolve an IMDB id for: " + title);
                if (confirmFirst) {
                    mainHandler.post(overlay::hide);
                }
                return;
            }
            Log.d(TAG, "Resolved " + title + " -> " + match);

            if (confirmFirst) {
                PlayerApp app = Preferences.getSelectedApp(this);
                mainHandler.post(() -> overlay.showConfirm(match, app, () -> PlayerLauncher.open(this, match)));
            } else {
                PlayerLauncher.open(this, match);
            }
        });
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
            overlay.hide();
        }
    }
}
