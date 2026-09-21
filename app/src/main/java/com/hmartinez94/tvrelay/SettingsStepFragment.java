package com.hmartinez94.tvrelay;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Toast;

import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import java.util.ArrayList;
import java.util.List;

/**
 * Main menu: pick a player app and reach each group of settings.
 *
 * Regrouped 2026-09-21: the single long list (25 rows under 8 section
 * headers) became this short menu, where each group opens its own screen
 * (see the SettingsSubStepFragment subclasses). The player dropdown stays
 * inline here since it's the one setting most people come to change. Two
 * rows carry a one-line status (Confirmation Overlay, Accessibility &
 * Permissions) because that state matters at a glance and would otherwise
 * be hidden a click away - Accessibility in particular is the critical
 * first-run step.
 *
 * The player app is a dropdown-style row using leanback's
 * GuidedAction.subActions - a single row that expands an inline popup of
 * choices. See onSubGuidedActionClicked() for the popup-selection handling,
 * which is separate from onGuidedActionClicked() (top-level rows only) since
 * sub-actions live in their own per-row list, not the fragment's main action
 * list.
 */
public class SettingsStepFragment extends GuidedStepSupportFragment {

    private static final long ACTION_PLAYER_APP = 1;
    private static final long ACTION_PLAYER_BASE = 100;
    private static final long ACTION_UPDATE_AVAILABLE = 2;
    private static final long ACTION_JELLYFIN_SERVER = 3;
    private static final long ACTION_FIRE_TV_MODE = 4;
    private static final long ACTION_DETECTION = 5;
    private static final long ACTION_OVERLAY = 6;
    private static final long ACTION_YOUTUBE = 7;
    private static final long ACTION_ACCESSIBILITY = 8;
    private static final long ACTION_MORE = 9;

    /** Throttle for the GitHub release check kicked off from onResume() - see maybeCheckForUpdate(). */
    private static final long UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;

    /** See onResume() - skips its rebuild on the very first call. */
    private boolean firstResume = true;

    @Override
    public GuidanceStylist.Guidance onCreateGuidance(Bundle savedInstanceState) {
        return new GuidanceStylist.Guidance(
                getString(R.string.app_name),
                getString(R.string.settings_description),
                null,
                null);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = super.onCreateView(inflater, container, savedInstanceState);
        // The adapter exists only once the view does. See ActionListDiff for why
        // this is required for the dropdown to keep opening after a rebuild.
        ActionListDiff.install(this);
        return view;
    }

    @Override
    public void onCreateActions(List<GuidedAction> actions, Bundle savedInstanceState) {
        actions.addAll(buildActions(requireContext()));
    }

    @Override
    public void onResume() {
        super.onResume();
        // Reflect state that might have changed while this screen wasn't
        // visible - the accessibility toggle or overlay permission, both
        // granted in system Settings (via a sub-screen) and only ever seen
        // again once the user returns here. Skipped on the very first
        // onResume() (right after onCreateActions() already built the
        // identical list moments earlier in onCreate()) - confirmed real bug
        // (2026-08-27): on a fresh install's first-ever visit to this screen,
        // this redundant rebuild had nothing new to reflect yet, but could
        // still replace the action list's views out from under a fast first
        // click - user report was the "Player app" dropdown not opening on
        // the first tap, only after clicking something else first, consistent
        // with that click landing mid-rebuild on a view about to be torn down.
        if (!firstResume) {
            setActions(buildActions(requireContext()));
        }
        firstResume = false;
        maybeCheckForUpdate();
    }

    /**
     * Throttled GitHub release check - see UPDATE_CHECK_INTERVAL_MS and
     * Preferences.getUpdateCheckedAt(). Only meaningful for a sideloaded
     * install (InstallSource.isPlayStoreInstall() - a Play install updates
     * through Play instead). Silent no-op on failure - this is a background
     * convenience check, not a user-initiated action that needs its own
     * error feedback (unlike UpdateStepFragment's actual download, which
     * does). A check that succeeds DOES say so either way, though - a toast
     * for "already current" and a separate one for "a newer version is now
     * available" (the settings row already reflects this too, via
     * isUpdateAvailable()/buildActions() - the toast is just an immediate
     * heads-up in case the user isn't looking at the row right then). Both
     * toasts are deliberately just passive displays that steal no focus and
     * interfere with nothing, per explicit user request (2026-08-26):
     * without them, "no update row appeared" was indistinguishable from "the
     * check never ran or failed".
     */
    private void maybeCheckForUpdate() {
        Context context = requireContext();
        if (InstallSource.isPlayStoreInstall(context)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - Preferences.getUpdateCheckedAt(context) < UPDATE_CHECK_INTERVAL_MS) {
            return;
        }
        Preferences.setUpdateCheckedAt(context, now);
        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            GithubReleaseClient.ReleaseInfo release = GithubReleaseClient.fetchLatest();
            if (release == null) {
                return;
            }
            if (!GithubReleaseClient.isNewer(release.version, BuildConfig.VERSION_NAME)) {
                // Already on the latest release - see the javadoc above.
                // Posted via the main Looper with the application context
                // (not runOnUiThread/requireContext()) so it still shows if
                // the user has already left this Settings screen by the
                // time the network call finishes.
                new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(appContext,
                        appContext.getString(R.string.update_up_to_date, BuildConfig.VERSION_NAME),
                        Toast.LENGTH_LONG).show());
                return;
            }
            Preferences.setUpdateAvailable(appContext, release.version, release.apkUrl);
            new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(appContext,
                    appContext.getString(R.string.update_available_toast, release.version),
                    Toast.LENGTH_LONG).show());
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> setActions(buildActions(requireContext())));
        }).start();
    }

    /** Whether a cached, still-newer-than-installed release is on hand - see maybeCheckForUpdate(). */
    private static boolean isUpdateAvailable(Context context) {
        if (InstallSource.isPlayStoreInstall(context)) {
            return false;
        }
        String latest = Preferences.getUpdateLatestVersion(context);
        return !latest.isEmpty() && GithubReleaseClient.isNewer(latest, BuildConfig.VERSION_NAME);
    }

    private List<GuidedAction> buildActions(Context context) {
        List<GuidedAction> actions = new ArrayList<>();

        // Surfaced at the very top, above every other row, so it's the first
        // thing seen. See maybeCheckForUpdate() for how this gets populated;
        // the toast it also shows is only a one-time heads-up, this row is
        // what actually persists.
        if (isUpdateAvailable(context)) {
            actions.add(new GuidedAction.Builder(context)
                    .id(ACTION_UPDATE_AVAILABLE)
                    .title(getString(R.string.settings_update_available))
                    .description(getString(R.string.settings_update_available_description,
                            Preferences.getUpdateLatestVersion(context)))
                    .build());
        }

        actions.add(buildPlayerAppAction(context));
        if (Preferences.getSelectedApp(context).usesJellyfinServer()) {
            // Only relevant while Jellyfin, Wholphin, or Moonfin is actually
            // selected - all three connect to the same kind of server and
            // share this same opt-in config (see PlayerApp.usesJellyfinServer())
            // - see JellyfinSettingsStepFragment/onSubGuidedActionClicked()
            // below for how this row's presence stays in sync with the player
            // dropdown above it.
            actions.add(buildJellyfinServerAction(context));
        }

        if (FireTvSupport.isFireTv(context)) {
            // Fire TV can't use the click pipeline at all (the accessibility
            // service receives zero events on the Fire TV home screen - see
            // FireTvWatcherService) - this row opens the UsageStats + OCR
            // activation flow instead. Only shown on a Fire TV device; on
            // Google TV the normal click path works and this would just be a
            // worse, permission-heavier duplicate.
            actions.add(new GuidedAction.Builder(context)
                    .id(ACTION_FIRE_TV_MODE)
                    .title(getString(R.string.settings_fire_tv_mode))
                    .description(getString(Preferences.isFireTvModeEnabled(context)
                            ? R.string.settings_fire_tv_status_enabled
                            : R.string.settings_fire_tv_status_disabled))
                    .build());
        }

        actions.add(groupAction(context, ACTION_DETECTION, R.string.settings_section_detection, null));
        actions.add(groupAction(context, ACTION_OVERLAY, R.string.settings_section_overlay,
                getString(Settings.canDrawOverlays(context)
                        ? R.string.settings_summary_enabled
                        : R.string.settings_summary_not_enabled)));
        actions.add(groupAction(context, ACTION_YOUTUBE, R.string.settings_section_youtube, null));
        actions.add(groupAction(context, ACTION_ACCESSIBILITY, R.string.settings_section_accessibility,
                getString(AccessibilitySettingsStepFragment.isAccessibilityServiceEnabled(context)
                        ? R.string.settings_summary_enabled
                        : R.string.settings_summary_not_enabled)));
        actions.add(groupAction(context, ACTION_MORE, R.string.settings_section_more, null));

        return actions;
    }

    /** A row that just opens a group's own screen; description is an optional one-line status. */
    private GuidedAction groupAction(Context context, long id, int titleRes, String description) {
        GuidedAction.Builder builder = new GuidedAction.Builder(context)
                .id(id)
                .title(getString(titleRes));
        if (description != null) {
            builder.description(description);
        }
        return builder.build();
    }

    /** The player-app row: a dropdown (subActions) rather than N separate top-level radio rows - see class doc. */
    private GuidedAction buildPlayerAppAction(Context context) {
        PlayerApp selected = Preferences.getSelectedApp(context);
        List<GuidedAction> subActions = new ArrayList<>();
        PlayerApp[] apps = PlayerApp.values();
        for (int i = 0; i < apps.length; i++) {
            PlayerApp app = apps[i];
            if (!app.isEnabled()) {
                // Disabled, not removed - see PlayerApp.isEnabled()
                // (currently: Plex, Wako). Index i is left as-is (not renumbered)
                // so ACTION_PLAYER_BASE + i still lines up with
                // PlayerApp.values() in onSubGuidedActionClicked below.
                continue;
            }
            GuidedAction.Builder builder = new GuidedAction.Builder(context)
                    .id(ACTION_PLAYER_BASE + i)
                    .title(app.getLabel())
                    .checkSetId(GuidedAction.DEFAULT_CHECK_SET_ID)
                    .checked(app == selected);
            // Plex/Jellyfin default to a title search hand-off, not a direct
            // open like Nuvio/Stremio - see PlayerApp.usesTitleSearch(). Said
            // plainly here so it's never mistaken for the same guarantee.
            // Jellyfin's row description here doesn't mention the opt-in
            // direct-open path (Preferences.isJellyfinLibraryLookupReady()) -
            // that's surfaced instead via the separate "Configure Jellyfin"
            // row this fragment adds right below the dropdown when Jellyfin
            // is selected (see buildJellyfinServerAction()).
            if (app.getDescriptionRes() != 0) {
                builder.description(getString(app.getDescriptionRes()));
            }
            subActions.add(builder.build());
        }
        return new GuidedAction.Builder(context)
                .id(ACTION_PLAYER_APP)
                .title(getString(R.string.settings_player_app))
                .description(selected.getLabel())
                .subActions(subActions)
                .build();
    }

    /** The "Configure Jellyfin server" row - only added while Jellyfin, Wholphin, or Moonfin is selected, see buildActions(). */
    private GuidedAction buildJellyfinServerAction(Context context) {
        PlayerApp selected = Preferences.getSelectedApp(context);
        String description;
        if (Preferences.isJellyfinLibraryLookupReady(context)) {
            description = getString(R.string.settings_jellyfin_configured_description, Preferences.getJellyfinUrl(context));
        } else if (selected.hasTitleSearchFallback()) {
            description = getString(R.string.settings_jellyfin_not_configured, selected.getLabel());
        } else {
            // Moonfin: unlike Jellyfin/Wholphin, leaving this unset doesn't
            // fall back to "will just open a search" - it has no search
            // screen at all (see PlayerApp.hasTitleSearchFallback()), so a
            // click just fails outright until this is set up.
            description = getString(R.string.settings_jellyfin_not_configured_no_search, selected.getLabel());
        }
        return new GuidedAction.Builder(context)
                .id(ACTION_JELLYFIN_SERVER)
                .title(getString(R.string.settings_jellyfin_configure))
                .description(description)
                .build();
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        long id = action.getId();
        if (id == ACTION_DETECTION) {
            GuidedStepSupportFragment.add(getFragmentManager(), new DetectionSettingsStepFragment());
        } else if (id == ACTION_OVERLAY) {
            GuidedStepSupportFragment.add(getFragmentManager(), new OverlaySettingsStepFragment());
        } else if (id == ACTION_YOUTUBE) {
            GuidedStepSupportFragment.add(getFragmentManager(), new YouTubeSettingsStepFragment());
        } else if (id == ACTION_ACCESSIBILITY) {
            GuidedStepSupportFragment.add(getFragmentManager(), new AccessibilitySettingsStepFragment());
        } else if (id == ACTION_MORE) {
            GuidedStepSupportFragment.add(getFragmentManager(), new MoreSettingsStepFragment());
        } else if (id == ACTION_JELLYFIN_SERVER) {
            GuidedStepSupportFragment.add(getFragmentManager(), new JellyfinSettingsStepFragment());
        } else if (id == ACTION_FIRE_TV_MODE) {
            GuidedStepSupportFragment.add(getFragmentManager(), new FireTvModeStepFragment());
        } else if (id == ACTION_UPDATE_AVAILABLE) {
            GuidedStepSupportFragment.add(getFragmentManager(), new UpdateWarningStepFragment());
        }
    }

    /**
     * Handles a choice made inside the player dropdown's inline popup -
     * separate from onGuidedActionClicked() above because sub-actions live in
     * their own per-row list (GuidedAction.getSubActions()), not the
     * fragment's main action list, so getActions().indexOf(action)/
     * notifyActionChanged(int) don't apply to them directly. Returning true
     * closes the popup back to the normal action list, per
     * GuidedStepSupportFragment's contract.
     */
    @Override
    public boolean onSubGuidedActionClicked(GuidedAction action) {
        Context context = requireContext();
        long id = action.getId();

        if (id >= ACTION_PLAYER_BASE && id < ACTION_PLAYER_BASE + 100) {
            int index = (int) (id - ACTION_PLAYER_BASE);
            PlayerApp[] apps = PlayerApp.values();
            if (index >= 0 && index < apps.length && apps[index].isEnabled()) {
                boolean wasJellyfin = Preferences.getSelectedApp(context).usesJellyfinServer();
                PlayerApp chosen = apps[index];
                Preferences.setSelectedApp(context, chosen);
                GuidedAction parent = findActionById(ACTION_PLAYER_APP);
                if (parent != null) {
                    parent.setDescription(chosen.getLabel());
                    syncCheckedInList(parent.getSubActions(), id);
                    notifyActionChanged(getActions().indexOf(parent));
                }
                boolean isJellyfin = chosen.usesJellyfinServer();
                if (wasJellyfin || isJellyfin) {
                    // The "Configure Jellyfin server" row's very presence
                    // depends on this selection (see buildActions()), and its
                    // description names whichever of Jellyfin/Wholphin/Moonfin
                    // is selected (see buildJellyfinServerAction()) - so this
                    // also needs to rebuild on a straight swap between them
                    // (wasJellyfin == isJellyfin == true), not only on the
                    // row appearing/disappearing. The partial patch above
                    // only updates the dropdown's own row, so without this
                    // the server row wouldn't appear/disappear/relabel until
                    // Settings was left and re-entered. Posted to the next
                    // frame rather than run synchronously, since this callback
                    // fires while the popup is still dismissing.
                    View view = getView();
                    if (view != null) {
                        view.post(() -> {
                            if (isAdded()) {
                                setActions(buildActions(requireContext()));
                            }
                        });
                    }
                }
            }
        }
        return true;
    }

    /**
     * Manually re-checks exactly the selected id within a dropdown's own
     * subActions list. Leanback's checkSetId auto-exclusivity is unreliable
     * on real hardware for the top-level action list (see
     * RadioActionHelper's class doc, a confirmed on-device bug) - this is
     * the same manual-exclusivity fix applied to a subActions list instead,
     * since there's no reason to expect the popup variant is any more
     * trustworthy and no on-device evidence either way yet.
     */
    private static void syncCheckedInList(List<GuidedAction> list, long selectedId) {
        if (list == null) {
            return;
        }
        for (GuidedAction candidate : list) {
            candidate.setChecked(candidate.getId() == selectedId);
        }
    }
}
