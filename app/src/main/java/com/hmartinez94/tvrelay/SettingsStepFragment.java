package com.hmartinez94.tvrelay;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import java.util.ArrayList;
import java.util.List;

/**
 * Main menu: choose a player app, enable the accessibility service, and reach
 * the rest of Settings.
 *
 * Redesigned 2026-08-25 for clarity: related rows are grouped under
 * non-interactive section-header actions (infoOnly - see addHeader()) rather
 * than one long undifferentiated list, and two multi-choice settings (player
 * app, YouTube redirect target) are now dropdown-style rows using leanback's
 * GuidedAction.subActions - a single row that expands an inline popup of
 * choices - instead of being spelled out as separate top-level radio rows.
 * See onSubGuidedActionClicked() for the popup-selection handling, which is
 * separate from onGuidedActionClicked() (top-level rows only) since sub-
 * actions live in their own per-row list, not the fragment's main action list.
 */
public class SettingsStepFragment extends GuidedStepSupportFragment {

    private static final long ACTION_PLAYER_APP = 1;
    private static final long ACTION_PLAYER_BASE = 100;
    private static final long ACTION_ENABLE_ACCESSIBILITY = 2;
    private static final long ACTION_RESTRICTED_SETTINGS_HELP = 3;
    private static final long ACTION_SHOW_CHOOSER = 4;
    private static final long ACTION_OCR_FALLBACK = 5;
    private static final long ACTION_SMARTTUBE_REDIRECT = 6;
    private static final long ACTION_YOUTUBE_TARGET = 7;
    private static final long ACTION_YOUTUBE_TARGET_BASE = 300;
    private static final long ACTION_ENABLE_OVERLAY = 8;
    private static final long ACTION_OVERLAY_REAPPEAR = 9;
    private static final long ACTION_SEARCH_MANUALLY = 10;
    private static final long ACTION_METADATA_PROVIDER = 11;
    private static final long ACTION_ABOUT = 12;
    private static final long ACTION_UPDATE_AVAILABLE = 13;
    private static final long ACTION_JELLYFIN_SERVER = 14;

    /** Throttle for the GitHub release check kicked off from onResume() - see maybeCheckForUpdate(). */
    private static final long UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;

    /** See onResume() - skips its rebuild on the very first call. */
    private boolean firstResume = true;

    // Section headers - infoOnly, so leanback skips them in D-pad focus
    // navigation and never routes a click to them. Distinct ids only matter
    // for leanback's internal list diffing (RecyclerView-style) when
    // buildActions() rebuilds the whole list - never branched on anywhere.
    private static final long ACTION_HEADER_PLAYER = 900;
    private static final long ACTION_HEADER_DETECTION = 901;
    private static final long ACTION_HEADER_YOUTUBE = 902;
    private static final long ACTION_HEADER_OVERLAY = 903;
    private static final long ACTION_HEADER_ACCESSIBILITY = 904;
    private static final long ACTION_HEADER_MORE = 905;
    private static final long ACTION_HEADER_UPDATE = 906;

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
        // Reflect state that might have changed while this screen wasn't
        // visible - the accessibility toggle or overlay permission, both
        // granted in system Settings and only ever seen again once the user
        // returns here. Skipped on the very first onResume() (right after
        // onCreateActions() already built the identical list moments
        // earlier in onCreate()) - confirmed real bug (2026-08-27): on a
        // fresh install's first-ever visit to this screen, this redundant
        // rebuild had nothing new to reflect yet, but could still replace
        // the action list's views out from under a fast first click - user
        // report was the "Player app" dropdown not opening on the first tap,
        // only after clicking something else first, consistent with that
        // click landing mid-rebuild on a view about to be torn down.
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
     * through Play instead, same gate shouldOfferRestrictedSettingsHelp()
     * uses for a similar sideload-only concern). Silent no-op on failure -
     * this is a background convenience check, not a user-initiated action
     * that needs its own error feedback (unlike UpdateStepFragment's actual
     * download, which does). A check that succeeds DOES say so either way,
     * though - a toast for "already current" and a separate one for "a
     * newer version is now available" (the settings row already reflects
     * this too, via isUpdateAvailable()/buildActions() - the toast is just
     * an immediate heads-up in case the user isn't looking at the row right
     * then). Both toasts are deliberately just passive displays that steal
     * no focus and interfere with nothing, per explicit user request
     * (2026-08-26): without them, "no update row appeared" was
     * indistinguishable from "the check never ran or failed".
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

        // Surfaced at the very top, above every other section, so it's the
        // first thing seen - a user who doesn't know to scroll down to
        // "More" would otherwise never notice it. See maybeCheckForUpdate()
        // for how this gets populated; the toast it also shows is only a
        // one-time heads-up, this row is what actually persists.
        if (isUpdateAvailable(context)) {
            addHeader(actions, context, ACTION_HEADER_UPDATE, R.string.settings_section_update);
            actions.add(new GuidedAction.Builder(context)
                    .id(ACTION_UPDATE_AVAILABLE)
                    .title(getString(R.string.settings_update_available))
                    .description(getString(R.string.settings_update_available_description,
                            Preferences.getUpdateLatestVersion(context)))
                    .build());
        }

        addHeader(actions, context, ACTION_HEADER_PLAYER, R.string.settings_section_player);
        actions.add(buildPlayerAppAction(context));
        if (Preferences.getSelectedApp(context).usesJellyfinServer()) {
            // Only relevant while Jellyfin or Wholphin is actually selected -
            // both connect to the same kind of server and share this same
            // opt-in config (see PlayerApp.usesJellyfinServer()) - see
            // JellyfinSettingsStepFragment/onSubGuidedActionClicked() below
            // for how this row's presence stays in sync with the player
            // dropdown above it.
            actions.add(buildJellyfinServerAction(context));
        }

        addHeader(actions, context, ACTION_HEADER_DETECTION, R.string.settings_section_detection);
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

        addHeader(actions, context, ACTION_HEADER_YOUTUBE, R.string.settings_section_youtube);
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
            // appear/disappear immediately - see its click handler below.
            actions.add(buildYouTubeTargetAction(context));
        }

        addHeader(actions, context, ACTION_HEADER_OVERLAY, R.string.settings_section_overlay);
        boolean overlayGranted = Settings.canDrawOverlays(context);
        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_ENABLE_OVERLAY)
                .title(getString(R.string.settings_enable_overlay))
                .description(getString(overlayGranted
                        ? R.string.settings_overlay_status_enabled
                        : R.string.settings_overlay_status_disabled))
                .build());
        boolean overlayReappearEnabled = Preferences.isOverlayReappearEnabled(context);
        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_OVERLAY_REAPPEAR)
                .title(getString(R.string.settings_overlay_reappear))
                .description(getString(overlayReappearEnabled
                        ? R.string.settings_overlay_reappear_status_enabled
                        : R.string.settings_overlay_reappear_status_disabled))
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(overlayReappearEnabled)
                .build());

        addHeader(actions, context, ACTION_HEADER_ACCESSIBILITY, R.string.settings_section_accessibility);
        boolean serviceEnabled = isAccessibilityServiceEnabled(context);
        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_ENABLE_ACCESSIBILITY)
                .title(getString(R.string.settings_enable_accessibility))
                .description(getString(serviceEnabled
                        ? R.string.settings_accessibility_status_enabled
                        : R.string.settings_accessibility_status_disabled))
                .build());
        if (shouldOfferRestrictedSettingsHelp(context, serviceEnabled)) {
            actions.add(new GuidedAction.Builder(context)
                    .id(ACTION_RESTRICTED_SETTINGS_HELP)
                    .title(getString(R.string.settings_restricted_settings_help))
                    .description(getString(R.string.settings_restricted_settings_help_description))
                    .build());
        }

        addHeader(actions, context, ACTION_HEADER_MORE, R.string.settings_section_more);
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

    private void addHeader(List<GuidedAction> actions, Context context, long id, int titleRes) {
        actions.add(new GuidedAction.Builder(context)
                .id(id)
                .title(getString(titleRes))
                .infoOnly(true)
                .focusable(false)
                .build());
    }

    /** The player-app row: a dropdown (subActions) rather than N separate top-level radio rows - see class doc. */
    private GuidedAction buildPlayerAppAction(Context context) {
        PlayerApp selected = Preferences.getSelectedApp(context);
        List<GuidedAction> subActions = new ArrayList<>();
        PlayerApp[] apps = PlayerApp.values();
        for (int i = 0; i < apps.length; i++) {
            PlayerApp app = apps[i];
            if (!app.isEnabled()) {
                // Disabled, not removed - see PlayerApp.isEnabled()/CLAUDE.md
                // (currently: Plex). Index i is left as-is (not renumbered)
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

    /** The "Configure Jellyfin server" row - only added while Jellyfin or Wholphin is selected, see buildActions(). */
    private GuidedAction buildJellyfinServerAction(Context context) {
        String description = Preferences.isJellyfinLibraryLookupReady(context)
                ? getString(R.string.settings_jellyfin_configured_description, Preferences.getJellyfinUrl(context))
                : getString(R.string.settings_jellyfin_not_configured, Preferences.getSelectedApp(context).getLabel());
        return new GuidedAction.Builder(context)
                .id(ACTION_JELLYFIN_SERVER)
                .title(getString(R.string.settings_jellyfin_configure))
                .description(description)
                .build();
    }

    /** The YouTube-redirect-target row: a dropdown between the two supported clients - see class doc. */
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
            return;
        }

        if (id == ACTION_SMARTTUBE_REDIRECT) {
            boolean enabled = !Preferences.isSmartTubeEnabled(context);
            Preferences.setSmartTubeEnabled(context, enabled);
            // Full rebuild, not a single notifyActionChanged: the YouTube
            // target dropdown row itself needs to appear/disappear
            // immediately along with this toggle - see buildActions().
            setActions(buildActions(context));
            return;
        }

        if (id == ACTION_OVERLAY_REAPPEAR) {
            boolean enabled = !Preferences.isOverlayReappearEnabled(context);
            Preferences.setOverlayReappearEnabled(context, enabled);
            action.setChecked(enabled);
            action.setDescription(getString(enabled
                    ? R.string.settings_overlay_reappear_status_enabled
                    : R.string.settings_overlay_reappear_status_disabled));
            notifyActionChanged(getActions().indexOf(action));
            return;
        }

        if (id == ACTION_OCR_FALLBACK) {
            if (!Preferences.isOcrDisclosureAccepted(context)) {
                // First time this row is turned on: route through its own
                // explicit consent screen (a materially bigger grant than
                // the base accessibility disclosure - see
                // OcrDisclosureStepFragment's class doc) instead of
                // toggling directly. It writes the pref itself and pops
                // back here, where onResume()'s existing rebuild-from-
                // Preferences picks up the new enabled state - same
                // mechanism PhonePairingStepFragment/
                // MetadataProviderStepFragment already rely on for their
                // own "write elsewhere, pop back" flows.
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
            return;
        }

        if (id == ACTION_ENABLE_ACCESSIBILITY) {
            Preferences.setAccessibilityEnableClickedAt(context, System.currentTimeMillis());
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } else if (id == ACTION_RESTRICTED_SETTINGS_HELP) {
            GuidedStepSupportFragment.add(getFragmentManager(), new RestrictedSettingsStepFragment());
        } else if (id == ACTION_ENABLE_OVERLAY) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + context.getPackageName())));
        } else if (id == ACTION_SEARCH_MANUALLY) {
            startActivity(new Intent(context, SearchActivity.class));
        } else if (id == ACTION_METADATA_PROVIDER) {
            GuidedStepSupportFragment.add(getFragmentManager(), new MetadataProviderStepFragment());
        } else if (id == ACTION_JELLYFIN_SERVER) {
            GuidedStepSupportFragment.add(getFragmentManager(), new JellyfinSettingsStepFragment());
        } else if (id == ACTION_ABOUT) {
            GuidedStepSupportFragment.add(getFragmentManager(), new AboutStepFragment());
        } else if (id == ACTION_UPDATE_AVAILABLE) {
            GuidedStepSupportFragment.add(getFragmentManager(), new UpdateStepFragment());
        }
    }

    /**
     * Handles a choice made inside either dropdown's inline popup (player
     * app, YouTube redirect target) - separate from onGuidedActionClicked()
     * above because sub-actions live in their own per-row list
     * (GuidedAction.getSubActions()), not the fragment's main action list,
     * so getActions().indexOf(action)/notifyActionChanged(int) don't apply
     * to them directly. Returning true closes the popup back to the normal
     * action list, per GuidedStepSupportFragment's contract.
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
                    // description names whichever of Jellyfin/Wholphin is
                    // selected (see buildJellyfinServerAction()) - so this
                    // also needs to rebuild on a straight swap between the
                    // two (wasJellyfin == isJellyfin == true), not only on
                    // the row appearing/disappearing. The partial patch above
                    // only updates the dropdown's own row, so without this
                    // the server row wouldn't appear/disappear/relabel until
                    // Settings was left and re-entered. Posted to the next
                    // frame, not called synchronously: this callback runs
                    // while the dropdown's own popup is still dismissing, and
                    // rebuilding the action list underneath it here (rather
                    // than deferred) is what the plan flagged as liable to
                    // misbehave.
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
            return true;
        }

        if (id >= ACTION_YOUTUBE_TARGET_BASE && id < ACTION_YOUTUBE_TARGET_BASE + 100) {
            int index = (int) (id - ACTION_YOUTUBE_TARGET_BASE);
            YouTubeRedirectTarget[] targets = YouTubeRedirectTarget.values();
            if (index >= 0 && index < targets.length) {
                YouTubeRedirectTarget chosen = targets[index];
                Preferences.setYouTubeRedirectTarget(context, chosen);
                GuidedAction parent = findActionById(ACTION_YOUTUBE_TARGET);
                if (parent != null) {
                    parent.setDescription(chosen.getLabel());
                    syncCheckedInList(parent.getSubActions(), id);
                    notifyActionChanged(getActions().indexOf(parent));
                }
            }
            return true;
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

    /**
     * Whether to offer the Restricted Settings walkthrough as an extra
     * Settings row. There's no direct API to ask "is this app
     * restricted-settings-blocked" (see CLAUDE.md's "capabilities wall" for
     * why this project generally has to infer OS-level restrictions rather
     * than query them directly) - so this combines the only three signals
     * actually available:
     *  - the service isn't currently enabled (nothing to help with otherwise)
     *  - it has never once actually connected (Preferences.
     *    hasAccessibilityServiceEverConnected()) - avoids a false positive
     *    for a service that worked before and was later turned off/crashed,
     *    which Restricted Settings (a first-enable-only block) can't explain
     *  - the user has actually clicked "Enable in Accessibility settings"
     *    before (Preferences.getAccessibilityEnableClickedAt() > 0) - avoids
     *    offering help before the user has even tried
     * ...and gates all of it behind InstallSource.isPlayStoreInstall(),
     * since Play is a trusted installer and is never subject to this
     * restriction in the first place - see CLAUDE.md's "Distribution &
     * monetization decisions" (a real Play Internal Testing release already
     * exists for this app).
     */
    private static boolean shouldOfferRestrictedSettingsHelp(Context context, boolean serviceEnabled) {
        if (serviceEnabled) {
            return false;
        }
        if (Preferences.hasAccessibilityServiceEverConnected(context)) {
            return false;
        }
        if (Preferences.getAccessibilityEnableClickedAt(context) <= 0) {
            return false;
        }
        return !InstallSource.isPlayStoreInstall(context);
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
