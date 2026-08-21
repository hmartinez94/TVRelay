package com.hmartinez94.tvrelay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.widget.Toast;

import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shows a QR code linking to docs/pair.html so the user can type a value
 * (currently: the TMDB API key) on their phone instead of the TV's
 * on-screen keyboard - see PhonePairing for how the phone's submission
 * actually reaches the TV. Purpose-built for the TMDB key for now rather
 * than a generic "receive any field" component; the same pattern could be
 * reused for Kodi credentials later if that turns out to be worth doing.
 */
public class PhonePairingStepFragment extends GuidedStepSupportFragment {

    private static final long ACTION_CANCEL = 1;
    private static final long TIMEOUT_MS = 5 * 60 * 1000;
    private static final int QR_SIZE_PX = 600;

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private String topic;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        topic = PhonePairing.newTopic();
        super.onCreate(savedInstanceState);
    }

    @Override
    public GuidanceStylist.Guidance onCreateGuidance(Bundle savedInstanceState) {
        String url = PhonePairing.pairingUrl(topic);
        Bitmap qr = QrCodeGenerator.generate(url, QR_SIZE_PX);
        Drawable icon = qr != null ? new BitmapDrawable(getResources(), qr) : null;
        return new GuidanceStylist.Guidance(
                getString(R.string.pairing_title),
                getString(R.string.pairing_description, url),
                getString(R.string.app_name),
                icon);
    }

    @Override
    public void onCreateActions(List<GuidedAction> actions, Bundle savedInstanceState) {
        actions.add(new GuidedAction.Builder(requireContext())
                .id(ACTION_CANCEL)
                .title(getString(R.string.pairing_cancel))
                .build());
        startWaiting();
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        if (action.getId() == ACTION_CANCEL) {
            cancelled.set(true);
            getFragmentManager().popBackStack();
        }
    }

    private void startWaiting() {
        Context appContext = requireContext().getApplicationContext();
        String pairingTopic = topic;
        new Thread(() -> {
            String value = PhonePairing.awaitValue(pairingTopic, TIMEOUT_MS);
            if (cancelled.get() || !isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                if (cancelled.get() || !isAdded()) {
                    return;
                }
                if (value == null) {
                    Toast.makeText(appContext, R.string.pairing_timed_out, Toast.LENGTH_LONG).show();
                    getFragmentManager().popBackStack();
                    return;
                }
                Preferences.setTmdbApiKey(appContext, value);
                Toast.makeText(appContext, R.string.pairing_received, Toast.LENGTH_SHORT).show();
                getFragmentManager().popBackStack();
            });
        }).start();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        cancelled.set(true);
    }
}
