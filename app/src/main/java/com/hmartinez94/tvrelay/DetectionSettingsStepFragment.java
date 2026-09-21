package com.hmartinez94.tvrelay;

import android.content.Context;
import android.content.Intent;

import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidedAction;

import java.util.ArrayList;
import java.util.List;

/** Detection & Matching: how a title is recognized and how an ambiguous match is handled. */
public class DetectionSettingsStepFragment extends SettingsSubStepFragment {

    private static final long ACTION_SHOW_CHOOSER = 1;
    private static final long ACTION_HIDE_ALTERNATE_TITLES = 2;
    private static final long ACTION_OCR_FALLBACK = 3;

    @Override
    protected int getTitleRes() {
        return R.string.settings_section_detection;
    }

    @Override
    protected int getDescriptionRes() {
        return R.string.settings_group_detection_description;
    }

    @Override
    protected List<GuidedAction> buildActions(Context context) {
        List<GuidedAction> actions = new ArrayList<>();

        boolean chooserEnabled = Preferences.isChooserEnabled(context);
        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_SHOW_CHOOSER)
                .title(getString(R.string.settings_show_chooser))
                .description(getString(chooserEnabled
                        ? R.string.settings_chooser_status_enabled
                        : R.string.settings_chooser_status_disabled))
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(chooserEnabled)
                .build());
        boolean alternateTitlesHidden = Preferences.isAlternateTitlesHidden(context);
        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_HIDE_ALTERNATE_TITLES)
                .title(getString(R.string.settings_hide_aka))
                .description(getString(alternateTitlesHidden
                        ? R.string.settings_hide_aka_status_enabled
                        : R.string.settings_hide_aka_status_disabled))
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(alternateTitlesHidden)
                .build());
        boolean ocrFallbackEnabled = Preferences.isOcrFallbackEnabled(context);
        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_OCR_FALLBACK)
                .title(getString(R.string.settings_ocr_fallback))
                .description(getString(ocrFallbackEnabled
                        ? R.string.settings_ocr_status_enabled
                        : R.string.settings_ocr_status_disabled))
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(ocrFallbackEnabled)
                .build());
        return actions;
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        Context context = requireContext();
        long id = action.getId();

        if (id == ACTION_SHOW_CHOOSER) {
            boolean enabled = !Preferences.isChooserEnabled(context);
            Preferences.setChooserEnabled(context, enabled);
            action.setChecked(enabled);
            action.setDescription(getString(enabled
                    ? R.string.settings_chooser_status_enabled
                    : R.string.settings_chooser_status_disabled));
            notifyActionChanged(getActions().indexOf(action));
        } else if (id == ACTION_HIDE_ALTERNATE_TITLES) {
            boolean hidden = !Preferences.isAlternateTitlesHidden(context);
            Preferences.setAlternateTitlesHidden(context, hidden);
            action.setChecked(hidden);
            action.setDescription(getString(hidden
                    ? R.string.settings_hide_aka_status_enabled
                    : R.string.settings_hide_aka_status_disabled));
            notifyActionChanged(getActions().indexOf(action));
        } else if (id == ACTION_OCR_FALLBACK) {
            if (!Preferences.isOcrDisclosureAccepted(context)) {
                // First time this row is turned on: route through its own
                // explicit consent screen (a materially bigger grant than
                // the base accessibility disclosure - see
                // OcrDisclosureStepFragment's class doc) instead of
                // toggling directly. It writes the pref itself and pops
                // back here, where onResume()'s rebuild-from-Preferences
                // picks up the new enabled state.
                GuidedStepSupportFragment.add(getFragmentManager(), new OcrDisclosureStepFragment());
                return;
            }
            boolean enabled = !Preferences.isOcrFallbackEnabled(context);
            Preferences.setOcrFallbackEnabled(context, enabled);
            if (!enabled) {
                context.stopService(new Intent(context, OcrCaptureForegroundService.class));
            }
            action.setChecked(enabled);
            action.setDescription(getString(enabled
                    ? R.string.settings_ocr_status_enabled
                    : R.string.settings_ocr_status_disabled));
            notifyActionChanged(getActions().indexOf(action));
        }
    }
}
