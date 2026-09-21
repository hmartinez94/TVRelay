package com.hmartinez94.tvrelay;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidedAction;

import java.util.ArrayList;
import java.util.List;

/** Accessibility & Permissions: enabling the service, and help when Android blocks it. */
public class AccessibilitySettingsStepFragment extends SettingsSubStepFragment {

    private static final long ACTION_ENABLE_ACCESSIBILITY = 1;
    private static final long ACTION_RESTRICTED_SETTINGS_HELP = 2;

    @Override
    protected int getTitleRes() {
        return R.string.settings_section_accessibility;
    }

    @Override
    protected int getDescriptionRes() {
        return R.string.settings_group_accessibility_description;
    }

    @Override
    protected List<GuidedAction> buildActions(Context context) {
        List<GuidedAction> actions = new ArrayList<>();
        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_ENABLE_ACCESSIBILITY)
                .title(getString(R.string.settings_enable_accessibility))
                .description(getString(isAccessibilityServiceEnabled(context)
                        ? R.string.settings_accessibility_status_enabled
                        : R.string.settings_accessibility_status_disabled))
                .build());
        if (shouldOfferRestrictedSettingsHelp(context)) {
            actions.add(new GuidedAction.Builder(context)
                    .id(ACTION_RESTRICTED_SETTINGS_HELP)
                    .title(getString(R.string.settings_restricted_settings_help))
                    .description(getString(R.string.settings_restricted_settings_help_description))
                    .build());
        }
        return actions;
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        long id = action.getId();
        if (id == ACTION_ENABLE_ACCESSIBILITY) {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } else if (id == ACTION_RESTRICTED_SETTINGS_HELP) {
            GuidedStepSupportFragment.add(getFragmentManager(), new RestrictedSettingsStepFragment());
        }
    }

    /**
     * Whether to offer the Restricted Settings walkthrough as an extra
     * row. Deliberately always shown for any sideloaded install
     * (2026-09-08, at explicit user request) rather than only appearing
     * after the user had already tried and failed to enable Accessibility -
     * a user who knows to look for it shouldn't have to trigger the failure
     * first, and there's no harm in a sideloaded user seeing it early.
     * Still gated behind InstallSource.isPlayStoreInstall(), since Play is a
     * trusted installer and is never subject to this restriction in the
     * first place (a real Play Internal Testing release already exists for
     * this app, confirming this).
     */
    private static boolean shouldOfferRestrictedSettingsHelp(Context context) {
        return !InstallSource.isPlayStoreInstall(context);
    }

    /** Also used by SettingsStepFragment for the main-menu status line. */
    static boolean isAccessibilityServiceEnabled(Context context) {
        String enabledServices = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabledServices)) {
            return false;
        }
        ComponentName target = new ComponentName(context, TvRelayAccessibilityService.class);
        for (String flattened : enabledServices.split(":")) {
            if (target.equals(ComponentName.unflattenFromString(flattened))) {
                return true;
            }
        }
        return false;
    }
}
