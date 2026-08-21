package com.hmartinez94.tvrelay;

import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;

import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import java.util.List;

/**
 * Optional Buy Me a Coffee link. Renders a QR code on-device (via
 * QrCodeGenerator) so the user can scan it with their phone rather than
 * typing a URL and card details with a D-pad.
 *
 * Grants nothing - no unlock, no feature change. That's what keeps this an
 * external donation link rather than something Play would reclassify as an
 * in-app purchase requiring Play Billing.
 */
public class DonateStepFragment extends GuidedStepSupportFragment {

    private static final long ACTION_CLOSE = 1;

    // Rendered larger than the default guidance icon slot will display it
    // at, so it stays sharp if scaled up. Whether the default icon slot is
    // actually big enough to comfortably scan from typical TV viewing
    // distance should be checked on a real screen (see plan verification
    // step 7) - if not, the fix is a custom GuidanceStylist layout with a
    // larger ImageView, not a bigger bitmap.
    private static final int QR_SIZE_PX = 600;

    @Override
    public GuidanceStylist.Guidance onCreateGuidance(Bundle savedInstanceState) {
        String url = getString(R.string.donate_url);
        Bitmap qr = QrCodeGenerator.generate(url, QR_SIZE_PX);
        Drawable icon = qr != null ? new BitmapDrawable(getResources(), qr) : null;
        return new GuidanceStylist.Guidance(
                getString(R.string.donate_title),
                getString(R.string.donate_description, url),
                getString(R.string.app_name),
                icon);
    }

    @Override
    public void onCreateActions(List<GuidedAction> actions, Bundle savedInstanceState) {
        actions.add(new GuidedAction.Builder(requireContext())
                .id(ACTION_CLOSE)
                .title(getString(R.string.donate_close))
                .build());
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        if (action.getId() == ACTION_CLOSE) {
            getFragmentManager().popBackStack();
        }
    }
}
