package com.hmartinez94.tvrelay;

import android.content.Context;

import androidx.leanback.widget.GuidedAction;

import java.util.ArrayList;
import java.util.List;

/** YouTube Redirect: the on/off toggle and which YouTube TV client it targets. */
public class YouTubeSettingsStepFragment extends SettingsSubStepFragment {

    private static final long ACTION_SMARTTUBE_REDIRECT = 1;
    private static final long ACTION_YOUTUBE_TARGET = 2;
    private static final long ACTION_YOUTUBE_TARGET_BASE = 100;

    @Override
    protected int getTitleRes() {
        return R.string.settings_section_youtube;
    }

    @Override
    protected int getDescriptionRes() {
        return R.string.settings_group_youtube_description;
    }

    @Override
    protected List<GuidedAction> buildActions(Context context) {
        List<GuidedAction> actions = new ArrayList<>();
        boolean smartTubeEnabled = Preferences.isSmartTubeEnabled(context);
        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_SMARTTUBE_REDIRECT)
                .title(getString(R.string.settings_smarttube_redirect))
                .description(getString(smartTubeEnabled
                        ? R.string.settings_smarttube_status_enabled
                        : R.string.settings_smarttube_status_disabled))
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(smartTubeEnabled)
                .build());
        if (smartTubeEnabled) {
            // Only offered while the toggle above is on - nothing to target
            // otherwise. Rebuilding the whole list on that toggle (rather
            // than a single notifyActionChanged) is what makes this row
            // appear/disappear immediately - see onGuidedActionClicked().
            actions.add(buildYouTubeTargetAction(context));
        }
        return actions;
    }

    /** The target row: a dropdown (subActions) between the supported clients. */
    private GuidedAction buildYouTubeTargetAction(Context context) {
        YouTubeRedirectTarget selected = Preferences.getYouTubeRedirectTarget(context);
        List<GuidedAction> subActions = new ArrayList<>();
        YouTubeRedirectTarget[] targets = YouTubeRedirectTarget.values();
        for (int i = 0; i < targets.length; i++) {
            YouTubeRedirectTarget target = targets[i];
            GuidedAction.Builder builder = new GuidedAction.Builder(context)
                    .id(ACTION_YOUTUBE_TARGET_BASE + i)
                    .title(target.getLabel())
                    .checkSetId(GuidedAction.DEFAULT_CHECK_SET_ID)
                    .checked(target == selected);
            if (target.getDescriptionRes() != 0) {
                builder.description(getString(target.getDescriptionRes()));
            }
            subActions.add(builder.build());
        }
        return new GuidedAction.Builder(context)
                .id(ACTION_YOUTUBE_TARGET)
                .title(getString(R.string.settings_youtube_target))
                .description(selected.getLabel())
                .subActions(subActions)
                .build();
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        if (action.getId() == ACTION_SMARTTUBE_REDIRECT) {
            Context context = requireContext();
            Preferences.setSmartTubeEnabled(context, !Preferences.isSmartTubeEnabled(context));
            setActions(buildActions(context));
        }
    }

    /**
     * Handles a choice made inside the dropdown's inline popup - separate from
     * onGuidedActionClicked() because sub-actions live in their own per-row
     * list, not the fragment's main action list. Returning true closes the
     * popup, per GuidedStepSupportFragment's contract.
     */
    @Override
    public boolean onSubGuidedActionClicked(GuidedAction action) {
        long id = action.getId();
        if (id >= ACTION_YOUTUBE_TARGET_BASE && id < ACTION_YOUTUBE_TARGET_BASE + 100) {
            int index = (int) (id - ACTION_YOUTUBE_TARGET_BASE);
            YouTubeRedirectTarget[] targets = YouTubeRedirectTarget.values();
            if (index >= 0 && index < targets.length) {
                YouTubeRedirectTarget chosen = targets[index];
                Preferences.setYouTubeRedirectTarget(requireContext(), chosen);
                GuidedAction parent = findActionById(ACTION_YOUTUBE_TARGET);
                if (parent != null) {
                    parent.setDescription(chosen.getLabel());
                    // Manual exclusivity: leanback's checkSetId auto-exclusivity
                    // is unreliable on real hardware - see RadioActionHelper.
                    List<GuidedAction> list = parent.getSubActions();
                    if (list != null) {
                        for (GuidedAction candidate : list) {
                            candidate.setChecked(candidate.getId() == id);
                        }
                    }
                    notifyActionChanged(getActions().indexOf(parent));
                }
            }
        }
        return true;
    }
}
