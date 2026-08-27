package com.hmartinez94.tvrelay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.widget.Toast;

import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Shows a QR code linking to docs/pair.html so the user can type a value on
 * their phone instead of the TV's on-screen keyboard - see PhonePairing for
 * how the phone's submission actually reaches the TV. Originally
 * purpose-built for just the TMDB key; generalized (2026-08-26) to also
 * serve the Jellyfin server URL and API key (see JellyfinSettingsStepFragment)
 * via the static factories below - each targets exactly one Preferences
 * field, chosen by the caller, not auto-detected.
 */
public class PhonePairingStepFragment extends GuidedStepSupportFragment {

    private static final String ARG_TARGET = "target";

    private static final long ACTION_CANCEL = 1;
    private static final long ACTION_AUTO_CLOSE_NOTE = 2;
    private static final long ACTION_NETWORK_WARNING = 3;
    private static final long TIMEOUT_MS = 5 * 60 * 1000;
    private static final int QR_SIZE_PX = 600;

    // Material Amber 500 rather than a pure #FFFF00 - confirmed real bug
    // this warning exists for (2026-08-27): a user's own router/DNS filter
    // was silently blocking ntfy.sh (the relay this whole flow depends on -
    // see PhonePairing), so every poll attempt timed out and the screen
    // just sat here until the 5-minute timeout, with no way to tell "this
    // will never work on this network" from "still waiting normally".
    private static final int WARNING_COLOR = Color.parseColor("#FFC107");

    /**
     * Which Preferences field a successful receive writes to, and the
     * label shown both on the TV (so the QR title/description are
     * unambiguous) and on the phone page itself (via PhonePairing.
     * pairingUrl()'s label param) - see pair.html.
     */
    private enum Target {
        TMDB_KEY(R.string.metadata_tmdb_key_field, Preferences::setTmdbApiKey),
        JELLYFIN_URL(R.string.jellyfin_field_url, Preferences::setJellyfinUrl),
        JELLYFIN_API_KEY(R.string.jellyfin_field_api_key, Preferences::setJellyfinApiKey);

        final int labelRes;
        final BiConsumer<Context, String> setter;

        Target(int labelRes, BiConsumer<Context, String> setter) {
            this.labelRes = labelRes;
            this.setter = setter;
        }
    }

    private final PhonePairing.Session session = new PhonePairing.Session();
    private String topic;
    private Target target;

    static PhonePairingStepFragment forTmdbKey() {
        return create(Target.TMDB_KEY);
    }

    static PhonePairingStepFragment forJellyfinUrl() {
        return create(Target.JELLYFIN_URL);
    }

    static PhonePairingStepFragment forJellyfinApiKey() {
        return create(Target.JELLYFIN_API_KEY);
    }

    private static PhonePairingStepFragment create(Target target) {
        PhonePairingStepFragment fragment = new PhonePairingStepFragment();
        Bundle args = new Bundle();
        args.putString(ARG_TARGET, target.name());
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Loaded before super.onCreate() - see MetadataProviderStepFragment
        // for the crash this ordering avoids (onCreateActions() runs inside
        // GuidedStepSupportFragment.onCreate()).
        Bundle args = getArguments();
        target = Target.valueOf(args.getString(ARG_TARGET));
        topic = PhonePairing.newTopic();
        super.onCreate(savedInstanceState);
    }

    @Override
    public GuidanceStylist.Guidance onCreateGuidance(Bundle savedInstanceState) {
        String label = getString(target.labelRes);
        String url = PhonePairing.pairingUrl(topic, label);
        Bitmap qr = QrCodeGenerator.generate(url, QR_SIZE_PX);
        Drawable icon = qr != null ? new BitmapDrawable(getResources(), qr) : null;
        return new GuidanceStylist.Guidance(
                getString(R.string.pairing_title),
                getString(R.string.pairing_description, url),
                label,
                icon);
    }

    @Override
    public void onCreateActions(List<GuidedAction> actions, Bundle savedInstanceState) {
        actions.add(new GuidedAction.Builder(requireContext())
                .id(ACTION_CANCEL)
                .title(getString(R.string.pairing_cancel))
                .build());
        // Sets expectations before the network-block warning below it -
        // otherwise a user has no way to tell "still waiting normally" from
        // "something's wrong" while this screen just sits there. Same
        // infoOnly/non-focusable info-row pattern as the warning, default
        // text color (only the warning below needs to stand out in yellow).
        //
        // Both this and the warning row below put their text in
        // description(), not title() - confirmed real bug (2026-08-27,
        // screenshot evidence): a GuidedAction's title renders single-line
        // and silently clips ("...once the value i", "...blocking the
        // pair"), while description wraps across multiple lines (already
        // relied on elsewhere in this app - e.g. JellyfinSettingsStepFragment's
        // "Status: enabled - ..." rows). title() is left as an empty string
        // rather than never called, to avoid depending on how leanback's
        // binding code handles a null title across versions.
        actions.add(new GuidedAction.Builder(requireContext())
                .id(ACTION_AUTO_CLOSE_NOTE)
                .title("")
                .description(getString(R.string.pairing_auto_close_note))
                .infoOnly(true)
                .focusable(false)
                .build());
        // A colored, non-clickable info row rather than appending to the
        // Guidance description (the left-hand panel shared with the QR
        // code) - confirmed real bug (2026-08-27): that panel has a fixed
        // height with no scrolling, and the QR bitmap alone was already
        // enough to push/clip the description text (a user reported the
        // pairing URL itself was already getting cut off there, before this
        // warning was even added). The actions panel on the right has no
        // such constraint (see the title()-vs-description() note above for
        // why it's the description field specifically). GuidedAction.
        // Builder.description() takes a plain CharSequence (unlike
        // GuidanceStylist.Guidance's constructor, String-only - confirmed by
        // a failed compile), so the color span can be set directly with no
        // stylist subclassing needed.
        SpannableString warning = new SpannableString(getString(R.string.pairing_network_warning));
        warning.setSpan(new ForegroundColorSpan(WARNING_COLOR), 0, warning.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        actions.add(new GuidedAction.Builder(requireContext())
                .id(ACTION_NETWORK_WARNING)
                .title("")
                .description(warning)
                .infoOnly(true)
                .focusable(false)
                .build());
        startWaiting();
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        if (action.getId() == ACTION_CANCEL) {
            session.cancel();
            getFragmentManager().popBackStack();
        }
    }

    private void startWaiting() {
        Context appContext = requireContext().getApplicationContext();
        String pairingTopic = topic;
        Target pairingTarget = target;
        PhonePairing.Session pairingSession = session;
        new Thread(() -> {
            String value = PhonePairing.awaitValue(pairingTopic, TIMEOUT_MS, pairingSession);
            if (pairingSession.isCancelled() || !isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                if (pairingSession.isCancelled() || !isAdded()) {
                    return;
                }
                if (value == null) {
                    Toast.makeText(appContext, R.string.pairing_timed_out, Toast.LENGTH_LONG).show();
                    getFragmentManager().popBackStack();
                    return;
                }
                pairingTarget.setter.accept(appContext, value);
                Toast.makeText(appContext, R.string.pairing_received, Toast.LENGTH_SHORT).show();
                getFragmentManager().popBackStack();
            });
        }).start();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // cancel(), not just marking cancelled: aborts an in-flight poll
        // immediately (Call.cancel()) instead of leaving it to run out its
        // connect timeout - see PhonePairing.Session's javadoc for the real
        // leak this closes (up to 5 concurrent abandoned polling threads
        // observed live, enough to starve a brand-new attempt).
        session.cancel();
    }
}
