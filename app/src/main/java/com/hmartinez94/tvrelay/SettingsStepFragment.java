package com.hmartinez94.tvrelay;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import java.util.ArrayList;
import java.util.List;

/** Main menu: choose a player app, enable the accessibility service, and reach the rest of Settings. */
public class SettingsStepFragment extends GuidedStepSupportFragment {

    private static final long ACTION_PLAYER_BASE = 100;
    private static final long ACTION_ENABLE_ACCESSIBILITY = 1;
    private static final long ACTION_SHOW_CHOOSER = 2;
    private static final long ACTION_ABOUT = 4;
    private static final long ACTION_SEARCH_MANUALLY = 5;
    private static final long ACTION_ENABLE_OVERLAY = 6;
    private static final long ACTION_METADATA_PROVIDER = 7;
    private static final long ACTION_SMARTTUBE_REDIRECT = 8;

    @Override
    public GuidanceStylist.Guidance onCreateGuidance(Bundle savedInstanceState) {
        return new GuidanceStylist.Guidance(
                getString(R.string.app_name),
                getString(R.string.settings_description),
                null,
                null);
    }

    @Override
    public void onCreateActions(List<GuidedAction> actions, Bundle savedInstanceState) {
        actions.addAll(buildActions(requireContext()));
    }

    @Override
    public void onResume() {
        super.onResume();
        // Reflect the current accessibility toggle state - the user may
        // have just come back from the system Settings screen.
        setActions(buildActions(requireContext()));
    }

    private List<GuidedAction> buildActions(Context context) {
        List<GuidedAction> actions = new ArrayList<>();
        PlayerApp selected = Preferences.getSelectedApp(context);

        PlayerApp[] apps = PlayerApp.values();
        for (int i = 0; i < apps.length; i++) {
            PlayerApp app = apps[i];
            if (!app.isEnabled()) {
                // Disabled, not removed - see PlayerApp.isEnabled()/CLAUDE.md
                // (currently: Plex). Index i is left as-is (not
                // renumbered) so ACTION_PLAYER_BASE + i still lines up with
                // PlayerApp.values() in onGuidedActionClicked below.
                continue;
            }
            GuidedAction.Builder builder = new GuidedAction.Builder(context)
                    .id(ACTION_PLAYER_BASE + i)
                    .title(app.getLabel())
                    .checkSetId(GuidedAction.DEFAULT_CHECK_SET_ID)
                    .checked(app == selected);
            // Plex/Jellyfin only get a title search hand-off, not a direct
            // open - see PlayerApp.usesTitleSearch(). Said plainly here so
            // it's never mistaken for the same kind of open Nuvio/Stremio do.
            if (app.getDescriptionRes() != 0) {
                builder.description(getString(app.getDescriptionRes()));
            }
            actions.add(builder.build());
        }

        boolean serviceEnabled = isAccessibilityServiceEnabled(context);
        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_ENABLE_ACCESSIBILITY)
                .title(getString(R.string.settings_enable_accessibility))
                .description(getString(serviceEnabled
                        ? R.string.settings_accessibility_status_enabled
                        : R.string.settings_accessibility_status_disabled))
                .build());

        boolean overlayGranted = Settings.canDrawOverlays(context);
        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_ENABLE_OVERLAY)
                .title(getString(R.string.settings_enable_overlay))
                .description(getString(overlayGranted
                        ? R.string.settings_overlay_status_enabled
                        : R.string.settings_overlay_status_disabled))
                .build());

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
        Context context = requireContext();
        long id = action.getId();

        if (id >= ACTION_PLAYER_BASE) {
            int index = (int) (id - ACTION_PLAYER_BASE);
            PlayerApp[] apps = PlayerApp.values();
            if (index >= 0 && index < apps.length && apps[index].isEnabled()) {
                Preferences.setSelectedApp(context, apps[index]);
            }
            RadioActionHelper.enforceExclusivity(getActions(), id,
                    candidateId -> candidateId >= ACTION_PLAYER_BASE, this::notifyActionChanged);
            return;
        }

        if (id == ACTION_SHOW_CHOOSER) {
            boolean enabled = !Preferences.isChooserEnabled(context);
            Preferences.setChooserEnabled(context, enabled);
            action.setChecked(enabled);
            action.setDescription(getString(enabled
                    ? R.string.settings_chooser_status_enabled
                    : R.string.settings_chooser_status_disabled));
            notifyActionChanged(getActions().indexOf(action));
            return;
        }

        if (id == ACTION_SMARTTUBE_REDIRECT) {
            boolean enabled = !Preferences.isSmartTubeEnabled(context);
            Preferences.setSmartTubeEnabled(context, enabled);
            action.setChecked(enabled);
            action.setDescription(getString(enabled
                    ? R.string.settings_smarttube_status_enabled
                    : R.string.settings_smarttube_status_disabled));
            notifyActionChanged(getActions().indexOf(action));
            return;
        }

        if (id == ACTION_ENABLE_ACCESSIBILITY) {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } else if (id == ACTION_ENABLE_OVERLAY) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + context.getPackageName())));
        } else if (id == ACTION_SEARCH_MANUALLY) {
            startActivity(new Intent(context, SearchActivity.class));
        } else if (id == ACTION_METADATA_PROVIDER) {
            GuidedStepSupportFragment.add(getFragmentManager(), new MetadataProviderStepFragment());
        } else if (id == ACTION_ABOUT) {
            GuidedStepSupportFragment.add(getFragmentManager(), new AboutStepFragment());
        }
    }

    private static boolean isAccessibilityServiceEnabled(Context context) {
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
