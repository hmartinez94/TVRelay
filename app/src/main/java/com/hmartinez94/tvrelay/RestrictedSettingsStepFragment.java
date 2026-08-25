package com.hmartinez94.tvrelay;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;

import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import java.util.List;

/**
 * Interactive walkthrough offered when SettingsStepFragment infers the user
 * is stuck on Android 13+'s "Restricted settings" block (see CLAUDE.md's
 * "Restricted settings" background and the static explanation already in
 * about_restricted_settings). This screen deliberately only ever calls
 * startActivity() with documented public Settings intents, or reads
 * read-only state - it never writes Settings.Secure directly and never
 * calls AppOpsManager to grant anything, since WRITE_SECURE_SETTINGS is
 * signature/system-only and unobtainable by a normal app. This follows the
 * same pattern this project uses everywhere else it runs into an
 * OS-enforced restriction (see CLAUDE.md's "The capabilities wall"):
 * document it honestly and offer only what's actually possible, rather than
 * pretend a bypass exists.
 */
public class RestrictedSettingsStepFragment extends GuidedStepSupportFragment {

    private static final long ACTION_OPEN_APP_INFO = 1;
    private static final long ACTION_RETRY_ACCESSIBILITY = 2;
    private static final long ACTION_CLOSE = 3;

    @Override
    public GuidanceStylist.Guidance onCreateGuidance(Bundle savedInstanceState) {
        String description = getString(R.string.restricted_settings_help_intro)
                + "\n\n" + getString(R.string.about_restricted_settings);
        return new GuidanceStylist.Guidance(
                getString(R.string.restricted_settings_help_title),
                description,
                getString(R.string.app_name),
                null);
    }

    @Override
    public void onCreateActions(List<GuidedAction> actions, Bundle savedInstanceState) {
        actions.add(new GuidedAction.Builder(requireContext())
                .id(ACTION_OPEN_APP_INFO)
                .title(getString(R.string.restricted_settings_open_app_info))
                .description(getString(R.string.restricted_settings_open_app_info_description))
                .build());

        actions.add(new GuidedAction.Builder(requireContext())
                .id(ACTION_RETRY_ACCESSIBILITY)
                .title(getString(R.string.restricted_settings_retry_accessibility))
                .build());

        actions.add(new GuidedAction.Builder(requireContext())
                .id(ACTION_CLOSE)
                .title(getString(R.string.about_close))
                .build());
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        long id = action.getId();
        if (id == ACTION_OPEN_APP_INFO) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + requireContext().getPackageName())));
        } else if (id == ACTION_RETRY_ACCESSIBILITY) {
            Preferences.setAccessibilityEnableClickedAt(requireContext(), System.currentTimeMillis());
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } else if (id == ACTION_CLOSE) {
            getFragmentManager().popBackStack();
        }
    }
}
