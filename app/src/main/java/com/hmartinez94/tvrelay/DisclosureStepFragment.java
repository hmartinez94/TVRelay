package com.hmartinez94.tvrelay;

import android.content.Context;
import android.os.Bundle;

import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import java.util.List;

/**
 * First-run consent screen. Play policy requires prominent disclosure and
 * explicit consent before an AccessibilityService can act for a
 * non-accessibility purpose; TvRelayAccessibilityService itself also
 * refuses to act until Preferences.isDisclosureAccepted() is true, so this
 * gate holds regardless of distribution channel.
 */
public class DisclosureStepFragment extends GuidedStepSupportFragment {

    private static final long ACTION_ACCEPT = 1;

    @Override
    public GuidanceStylist.Guidance onCreateGuidance(Bundle savedInstanceState) {
        return new GuidanceStylist.Guidance(
                getString(R.string.disclosure_title),
                getString(R.string.disclosure_description),
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
            Preferences.setDisclosureAccepted(requireContext(), true);
            GuidedStepSupportFragment.add(getFragmentManager(), new SettingsStepFragment());
        }
    }
}
