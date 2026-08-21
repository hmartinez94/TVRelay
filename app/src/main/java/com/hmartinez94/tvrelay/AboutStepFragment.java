package com.hmartinez94.tvrelay;

import android.os.Bundle;

import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import java.util.List;

/** Legal notice, TheTVDB attribution (required by its free-tier license), and the Restricted Settings workaround. */
public class AboutStepFragment extends GuidedStepSupportFragment {

    private static final long ACTION_CLOSE = 1;

    @Override
    public GuidanceStylist.Guidance onCreateGuidance(Bundle savedInstanceState) {
        String description = getString(R.string.about_legal)
                + "\n\n" + getString(R.string.about_tvdb_attribution)
                + "\n\n" + getString(R.string.about_restricted_settings);
        return new GuidanceStylist.Guidance(
                getString(R.string.about_title),
                description,
                getString(R.string.app_name),
                null);
    }

    @Override
    public void onCreateActions(List<GuidedAction> actions, Bundle savedInstanceState) {
        actions.add(new GuidedAction.Builder(requireContext())
                .id(ACTION_CLOSE)
                .title(getString(R.string.about_close))
                .build());
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        if (action.getId() == ACTION_CLOSE) {
            getFragmentManager().popBackStack();
        }
    }
}
