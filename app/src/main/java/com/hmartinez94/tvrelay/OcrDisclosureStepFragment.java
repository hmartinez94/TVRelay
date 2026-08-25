package com.hmartinez94.tvrelay;

import android.content.Context;
import android.os.Bundle;

import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import java.util.List;

/**
 * Separate, explicit consent screen for the screen-reading (MediaProjection
 * + on-device OCR) fallback - see Preferences.isOcrFallbackEnabled()/
 * isOcrDisclosureAccepted() and TvRelayAccessibilityService's two trigger
 * points. Reached from Settings (not the app's first-run root, unlike
 * DisclosureStepFragment) the first time the user turns the fallback on.
 *
 * Deliberately a separate screen/flag from the base accessibility-service
 * disclosure: MediaProjection is a materially bigger, more visible grant
 * (a persistent system recording/casting indicator for as long as this is
 * armed, not just during an actual capture) and deserves its own explicit
 * opt-in rather than riding along with the base consent.
 */
public class OcrDisclosureStepFragment extends GuidedStepSupportFragment {

    private static final long ACTION_ACCEPT = 1;

    @Override
    public GuidanceStylist.Guidance onCreateGuidance(Bundle savedInstanceState) {
        return new GuidanceStylist.Guidance(
                getString(R.string.ocr_disclosure_title),
                getString(R.string.ocr_disclosure_description),
                getString(R.string.app_name),
                null);
    }

    @Override
    public void onCreateActions(List<GuidedAction> actions, Bundle savedInstanceState) {
        Context context = requireContext();
        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_ACCEPT)
                .title(getString(R.string.disclosure_accept))
                .build());
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        if (action.getId() == ACTION_ACCEPT) {
            Context context = requireContext();
            // Accept-and-enable in one step - reads more naturally here
            // than making the user accept, then separately go find and
            // toggle the checkbox row that brought them here in the first
            // place.
            Preferences.setOcrDisclosureAccepted(context, true);
            Preferences.setOcrFallbackEnabled(context, true);
            // Reached from SettingsStepFragment via
            // GuidedStepSupportFragment.add(), so pop back to it rather
            // than pushing a new instance forward - same pattern as
            // MetadataProviderStepFragment's Save action and
            // PhonePairingStepFragment's cancel/receive paths.
            // getParentFragmentManager() doesn't compile in this project
            // (leanback 1.2.0 pulls in an older transitive
            // androidx.fragment version) - use getFragmentManager().
            getFragmentManager().popBackStack();
        }
    }
}
