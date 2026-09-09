package com.hmartinez94.tvrelay;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import java.util.List;

/**
 * Activation/disclosure screen for Fire TV mode - reached from
 * SettingsStepFragment's "Fire TV mode" row (only shown on a Fire TV
 * device, see FireTvSupport.isFireTv()). Fire TV mode reads a
 * recommendation's title off the screen via UsageStats + on-device OCR
 * (FireTvWatcherService), the only approach that works on the Fire TV home
 * screen - the accessibility service receives zero events there at all, not
 * even window/focus events (see FireTvWatcherService's class doc).
 *
 * It needs two one-time grants - Usage Access and Screen Recording - so this
 * screen walks the user through them rather than a plain checkbox toggle
 * (like OcrDisclosureStepFragment does for the Google TV OCR fallback, but
 * with the extra usage-access step). Because screen-recording consent does
 * not survive a reboot/power loss, activation is an explicit action the user
 * re-runs after a reboot, not a persistent switch - see the reference app's
 * documented behavior and FireTvWatcherService's START_NOT_STICKY.
 */
public class FireTvModeStepFragment extends GuidedStepSupportFragment {

    private static final long ACTION_USAGE_ACCESS = 1;
    private static final long ACTION_ACTIVATE = 2;
    private static final long ACTION_DEACTIVATE = 3;
    private static final long ACTION_CLOSE = 4;

    @Override
    public GuidanceStylist.Guidance onCreateGuidance(Bundle savedInstanceState) {
        return new GuidanceStylist.Guidance(
                getString(R.string.fire_tv_mode_title),
                getString(R.string.fire_tv_mode_description),
                getString(R.string.app_name),
                null);
    }

    @Override
    public void onCreateActions(List<GuidedAction> actions, Bundle savedInstanceState) {
        Context context = requireContext();
        boolean usageGranted = FireTvSupport.hasUsageAccess(context);
        boolean enabled = Preferences.isFireTvModeEnabled(context);

        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_USAGE_ACCESS)
                .title(getString(R.string.fire_tv_mode_usage_access))
                .description(getString(usageGranted
                        ? R.string.fire_tv_mode_usage_access_granted
                        : R.string.fire_tv_mode_usage_access_not_granted))
                .build());

        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_ACTIVATE)
                .title(getString(enabled
                        ? R.string.fire_tv_mode_reactivate
                        : R.string.fire_tv_mode_activate))
                .description(getString(enabled
                        ? R.string.fire_tv_mode_active_description
                        : R.string.fire_tv_mode_inactive_description))
                .build());

        if (enabled) {
            actions.add(new GuidedAction.Builder(context)
                    .id(ACTION_DEACTIVATE)
                    .title(getString(R.string.fire_tv_mode_deactivate))
                    .build());
        }

        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_CLOSE)
                .title(getString(R.string.about_close))
                .build());
    }

    @Override
    public void onResume() {
        super.onResume();
        // Usage Access is granted on a separate system screen, so re-read it
        // (and the enabled state) whenever the user returns here.
        setActions(buildFreshActions());
    }

    private List<GuidedAction> buildFreshActions() {
        List<GuidedAction> actions = new java.util.ArrayList<>();
        onCreateActions(actions, null);
        return actions;
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        Context context = requireContext();
        long id = action.getId();

        if (id == ACTION_USAGE_ACCESS) {
            // Fire OS wires the standard ACTION_USAGE_ACCESS_SETTINGS intent
            // to a do-nothing CTS compliance stub (CTSDummySettingActivity)
            // that flashes blank and closes - confirmed on-device 2026-09-07
            // via logcat (ActivityTaskManager START ... CTSDummySettingActivity
            // -> "Top activity paused and cleared"). So land on the real Fire
            // OS Apps settings screen instead; the user goes to Special app
            // access -> Usage access -> TVRelay from there. Falls back to the
            // standard intent (correct on Google TV / real Android, where
            // this fragment doesn't normally appear, and on any Fire OS build
            // that renames the activity below). Requires com.amazon.tv.settings.v2
            // in the manifest <queries> for the explicit component to resolve
            // under Android 11+ package visibility.
            try {
                Intent fireApps = new Intent();
                fireApps.setClassName("com.amazon.tv.settings.v2",
                        "com.amazon.tv.settings.v2.tv.applications.ApplicationsActivity");
                startActivity(fireApps);
            } catch (Exception firstFailure) {
                try {
                    startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                } catch (Exception e) {
                    Toast.makeText(context, R.string.fire_tv_mode_usage_settings_unavailable,
                            Toast.LENGTH_LONG).show();
                }
            }
        } else if (id == ACTION_ACTIVATE) {
            if (!FireTvSupport.hasUsageAccess(context)) {
                Toast.makeText(context, R.string.fire_tv_mode_usage_required_toast,
                        Toast.LENGTH_LONG).show();
                return;
            }
            // Records the screen-recording consent understanding (shared
            // with the Google TV OCR fallback's disclosure flag) and marks
            // the mode on. Starting the watcher brings up the one-time
            // screen-recording consent dialog itself (ensureSession()).
            Preferences.setOcrDisclosureAccepted(context, true);
            Preferences.setFireTvModeEnabled(context, true);
            FireTvWatcherService.start(context);
            Toast.makeText(context, R.string.fire_tv_mode_activated_toast, Toast.LENGTH_LONG).show();
            setActions(buildFreshActions());
        } else if (id == ACTION_DEACTIVATE) {
            Preferences.setFireTvModeEnabled(context, false);
            FireTvWatcherService.stop(context);
            setActions(buildFreshActions());
        } else if (id == ACTION_CLOSE) {
            getFragmentManager().popBackStack();
        }
    }
}
