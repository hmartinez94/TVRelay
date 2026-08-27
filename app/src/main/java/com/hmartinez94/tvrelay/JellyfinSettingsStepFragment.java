package com.hmartinez94.tvrelay;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Toast;

import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import java.util.List;

/**
 * Opt-in "find it in your own library first" setup for a Jellyfin server -
 * see PlayerLauncher.planTitleSearch()/JellyfinClient and Preferences.
 * isJellyfinLibraryLookupReady(). Reached from Settings' player-app row
 * while either Jellyfin or Wholphin is selected (see SettingsStepFragment/
 * PlayerApp.usesJellyfinServer()) - both connect to the same kind of server
 * and share this one config screen, since Wholphin is a separate,
 * from-scratch open-source Jellyfin client, not a fork, but uses the same
 * server-local item id contract (see PlayerApp's class doc). Structured
 * exactly like MetadataProviderStepFragment (editable fields + phone-pairing
 * rows + test + save), plus a leading checkbox that gates every row below
 * it: unchecked (the default) is byte-identical to Jellyfin/Wholphin's
 * original search-only behavior, with no server needed and no network call
 * ever attempted.
 */
public class JellyfinSettingsStepFragment extends GuidedStepSupportFragment {

    private static final long ACTION_LOOKUP_ENABLED = 1;
    private static final long ACTION_URL = 2;
    private static final long ACTION_API_KEY = 3;
    private static final long ACTION_PAIR_URL = 4;
    private static final long ACTION_PAIR_KEY = 5;
    private static final long ACTION_TEST = 6;
    private static final long ACTION_SAVE = 7;

    private boolean lookupEnabled;
    private String jellyfinUrl;
    private String jellyfinApiKey;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Loaded before super.onCreate(): GuidedStepSupportFragment.onCreate()
        // calls onCreateActions() internally, so fields it reads must
        // already be set - see MetadataProviderStepFragment for the crash
        // this ordering avoids.
        Context context = requireContext();
        lookupEnabled = Preferences.isJellyfinLibraryLookupEnabled(context);
        jellyfinUrl = Preferences.getJellyfinUrl(context);
        jellyfinApiKey = Preferences.getJellyfinApiKey(context);
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Picks up a value just received via phone pairing -
        // PhonePairingStepFragment writes straight to Preferences and pops
        // back to this screen, rather than passing a result back through
        // any fragment-to-fragment API - same mechanism
        // MetadataProviderStepFragment already relies on for the TMDB key.
        Context context = requireContext();
        String refreshedUrl = Preferences.getJellyfinUrl(context);
        String refreshedKey = Preferences.getJellyfinApiKey(context);
        if (refreshedUrl.equals(jellyfinUrl) && refreshedKey.equals(jellyfinApiKey)) {
            return;
        }
        jellyfinUrl = refreshedUrl;
        jellyfinApiKey = refreshedKey;
        List<GuidedAction> actions = getActions();
        for (int i = 0; i < actions.size(); i++) {
            GuidedAction action = actions.get(i);
            if (action.getId() == ACTION_URL) {
                action.setDescription(jellyfinUrl);
                notifyActionChanged(i);
            } else if (action.getId() == ACTION_API_KEY) {
                action.setDescription(jellyfinApiKey);
                notifyActionChanged(i);
            }
        }
    }

    @Override
    public GuidanceStylist.Guidance onCreateGuidance(Bundle savedInstanceState) {
        return new GuidanceStylist.Guidance(
                getString(R.string.jellyfin_settings_title),
                getString(R.string.jellyfin_settings_description),
                getString(R.string.app_name),
                null);
    }

    @Override
    public void onCreateActions(List<GuidedAction> actions, Bundle savedInstanceState) {
        Context context = requireContext();

        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_LOOKUP_ENABLED)
                .title(getString(R.string.jellyfin_lookup_enabled))
                .description(getString(lookupEnabled
                        ? R.string.jellyfin_lookup_status_enabled
                        : R.string.jellyfin_lookup_status_disabled))
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(lookupEnabled)
                .build());

        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_URL)
                .title(getString(R.string.jellyfin_field_url))
                .description(jellyfinUrl)
                .descriptionEditable(true)
                .descriptionEditInputType(InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_URI
                        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
                .enabled(lookupEnabled)
                .focusable(lookupEnabled)
                .build());

        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_API_KEY)
                .title(getString(R.string.jellyfin_field_api_key))
                .description(jellyfinApiKey)
                .descriptionEditable(true)
                .descriptionEditInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
                .enabled(lookupEnabled)
                .focusable(lookupEnabled)
                .build());

        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_PAIR_URL)
                .title(getString(R.string.jellyfin_pair_url))
                .description(getString(R.string.metadata_tmdb_pair_phone_description))
                .enabled(lookupEnabled)
                .focusable(lookupEnabled)
                .build());

        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_PAIR_KEY)
                .title(getString(R.string.jellyfin_pair_key))
                .description(getString(R.string.metadata_tmdb_pair_phone_description))
                .enabled(lookupEnabled)
                .focusable(lookupEnabled)
                .build());

        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_TEST)
                .title(getString(R.string.jellyfin_test_connection))
                .enabled(lookupEnabled)
                .focusable(lookupEnabled)
                .build());

        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_SAVE)
                .title(getString(R.string.metadata_provider_save))
                .build());
    }

    @Override
    public long onGuidedActionEditedAndProceed(GuidedAction action) {
        if (action.getId() == ACTION_URL) {
            jellyfinUrl = action.getDescription() != null ? action.getDescription().toString().trim() : "";
        } else if (action.getId() == ACTION_API_KEY) {
            jellyfinApiKey = action.getDescription() != null ? action.getDescription().toString().trim() : "";
        }
        return GuidedAction.ACTION_ID_NEXT;
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        Context context = requireContext();
        long id = action.getId();

        if (id == ACTION_LOOKUP_ENABLED) {
            lookupEnabled = !lookupEnabled;
            action.setChecked(lookupEnabled);
            action.setDescription(getString(lookupEnabled
                    ? R.string.jellyfin_lookup_status_enabled
                    : R.string.jellyfin_lookup_status_disabled));
            applyLookupGating();
            notifyActionChanged(getActions().indexOf(action));
        } else if (id == ACTION_PAIR_URL) {
            GuidedStepSupportFragment.add(getFragmentManager(), PhonePairingStepFragment.forJellyfinUrl());
        } else if (id == ACTION_PAIR_KEY) {
            GuidedStepSupportFragment.add(getFragmentManager(), PhonePairingStepFragment.forJellyfinApiKey());
        } else if (id == ACTION_TEST) {
            testConnection();
        } else if (id == ACTION_SAVE) {
            if (lookupEnabled && (jellyfinUrl.trim().isEmpty() || jellyfinApiKey.trim().isEmpty())) {
                // Saving here with the checkbox on but a field blank would
                // silently leave Preferences.isJellyfinLibraryLookupReady()
                // false with no explanation - refuse instead, same "guard
                // before writing" spirit as MetadataProviderStepFragment's
                // testKey() falling back to the bundled key rather than
                // failing unexplained.
                Toast.makeText(context, R.string.jellyfin_save_incomplete, Toast.LENGTH_LONG).show();
                return;
            }
            Preferences.setJellyfinLibraryLookupEnabled(context, lookupEnabled);
            Preferences.setJellyfinUrl(context, jellyfinUrl);
            Preferences.setJellyfinApiKey(context, jellyfinApiKey);
            Toast.makeText(context, R.string.metadata_provider_saved, Toast.LENGTH_SHORT).show();
            getFragmentManager().popBackStack();
        }
    }

    /**
     * Enables/disables (and shows/hides focusability of) every row that
     * only makes sense while library lookup is on - called both here (on
     * the checkbox toggle) and implicitly via onCreateActions() building
     * each row's initial state from the same lookupEnabled field, so the
     * two can't disagree. Rows stay visible but unfocusable rather than
     * disappearing - reads better on a D-pad than rows appearing/
     * disappearing under the cursor.
     */
    private void applyLookupGating() {
        List<GuidedAction> actions = getActions();
        for (int i = 0; i < actions.size(); i++) {
            GuidedAction candidate = actions.get(i);
            long id = candidate.getId();
            if (id == ACTION_URL || id == ACTION_API_KEY || id == ACTION_PAIR_URL
                    || id == ACTION_PAIR_KEY || id == ACTION_TEST) {
                candidate.setEnabled(lookupEnabled);
                candidate.setFocusable(lookupEnabled);
                notifyActionChanged(i);
            }
        }
    }

    private void testConnection() {
        Context appContext = requireContext().getApplicationContext();
        String url = jellyfinUrl;
        String apiKey = jellyfinApiKey;
        new Thread(() -> {
            JellyfinClient.ConnectionResult result = JellyfinClient.testConnection(appContext, url, apiKey);
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                if (result.isSuccess()) {
                    Toast.makeText(appContext, getString(R.string.jellyfin_test_success, result.serverName),
                            Toast.LENGTH_LONG).show();
                } else {
                    // errorDetail (JellyfinClient.testConnection()) is a
                    // specific reason (bad key vs bad URL vs cert issue),
                    // not a generic failure - see that method's javadoc for
                    // why this replaced a plain success/fail toast.
                    Toast.makeText(appContext, getString(R.string.jellyfin_test_failure, result.errorDetail),
                            Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }
}
