package com.hmartinez94.tvrelay;

import android.content.Context;
import android.content.Intent;

import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidedAction;

import java.util.ArrayList;
import java.util.List;

/** More: manual search, the metadata provider, and About & help. */
public class MoreSettingsStepFragment extends SettingsSubStepFragment {

    private static final long ACTION_SEARCH_MANUALLY = 1;
    private static final long ACTION_METADATA_PROVIDER = 2;
    private static final long ACTION_ABOUT = 3;

    @Override
    protected int getTitleRes() {
        return R.string.settings_section_more;
    }

    @Override
    protected int getDescriptionRes() {
        return R.string.settings_group_more_description;
    }

    @Override
    protected List<GuidedAction> buildActions(Context context) {
        List<GuidedAction> actions = new ArrayList<>();
        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_SEARCH_MANUALLY)
                .title(getString(R.string.settings_search_manually))
                .build());
        MetadataProvider provider = Preferences.getMetadataProvider(context);
        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_METADATA_PROVIDER)
                .title(getString(R.string.settings_metadata_provider))
                .description(provider == MetadataProvider.TMDB ? "TMDB" : "TheTVDB")
                .build());
        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_ABOUT)
                .title(getString(R.string.settings_about))
                .build());
        return actions;
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        long id = action.getId();
        if (id == ACTION_SEARCH_MANUALLY) {
            startActivity(new Intent(requireContext(), SearchActivity.class));
        } else if (id == ACTION_METADATA_PROVIDER) {
            GuidedStepSupportFragment.add(getFragmentManager(), new MetadataProviderStepFragment());
        } else if (id == ACTION_ABOUT) {
            GuidedStepSupportFragment.add(getFragmentManager(), new AboutStepFragment());
        }
    }
}
