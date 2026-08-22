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
 * Choose TheTVDB (default, no setup) or TMDB (requires the user's own
 * personal free API key - see TmdbClient for why it must be their own,
 * not one bundled with the app).
 */
public class MetadataProviderStepFragment extends GuidedStepSupportFragment {

    private static final long ACTION_PROVIDER_TVDB = 1;
    private static final long ACTION_PROVIDER_TMDB = 2;
    private static final long ACTION_TMDB_KEY = 3;
    private static final long ACTION_PAIR_PHONE = 4;
    private static final long ACTION_TEST_KEY = 5;
    private static final long ACTION_SAVE = 6;

    private MetadataProvider provider;
    private String tmdbApiKey;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Load before super.onCreate(): GuidedStepSupportFragment.onCreate()
        // calls onCreateActions() internally, so fields it reads must
        // already be set (see KodiSettingsStepFragment for the crash this
        // ordering avoids).
        Context context = requireContext();
        provider = Preferences.getMetadataProvider(context);
        tmdbApiKey = Preferences.getTmdbApiKey(context);
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Picks up a value just received via phone pairing -
        // PhonePairingStepFragment writes straight to Preferences and
        // pops back to this screen, rather than passing a result back
        // through any fragment-to-fragment API.
        Context context = requireContext();
        String refreshed = Preferences.getTmdbApiKey(context);
        if (!refreshed.equals(tmdbApiKey)) {
            tmdbApiKey = refreshed;
            List<GuidedAction> actions = getActions();
            for (int i = 0; i < actions.size(); i++) {
                if (actions.get(i).getId() == ACTION_TMDB_KEY) {
                    actions.get(i).setDescription(tmdbApiKey);
                    notifyActionChanged(i);
                    break;
                }
            }
        }
    }

    @Override
    public GuidanceStylist.Guidance onCreateGuidance(Bundle savedInstanceState) {
        return new GuidanceStylist.Guidance(
                getString(R.string.metadata_provider_title),
                getString(R.string.metadata_provider_description),
                getString(R.string.app_name),
                null);
    }

    @Override
    public void onCreateActions(List<GuidedAction> actions, Bundle savedInstanceState) {
        Context context = requireContext();

        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_PROVIDER_TVDB)
                .title(getString(R.string.metadata_provider_tvdb))
                .description(getString(R.string.metadata_provider_tvdb_description))
                .checkSetId(GuidedAction.DEFAULT_CHECK_SET_ID)
                .checked(provider == MetadataProvider.THETVDB)
                .build());

        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_PROVIDER_TMDB)
                .title(getString(R.string.metadata_provider_tmdb))
                .description(getString(R.string.metadata_provider_tmdb_description))
                .checkSetId(GuidedAction.DEFAULT_CHECK_SET_ID)
                .checked(provider == MetadataProvider.TMDB)
                .build());

        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_TMDB_KEY)
                .title(getString(R.string.metadata_tmdb_key_field))
                .description(tmdbApiKey)
                .descriptionEditable(true)
                .descriptionEditInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
                .build());

        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_PAIR_PHONE)
                .title(getString(R.string.metadata_tmdb_pair_phone))
                .description(getString(R.string.metadata_tmdb_pair_phone_description))
                .build());

        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_TEST_KEY)
                .title(getString(R.string.metadata_tmdb_test_key))
                .build());

        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_SAVE)
                .title(getString(R.string.metadata_provider_save))
                .build());
    }

    @Override
    public long onGuidedActionEditedAndProceed(GuidedAction action) {
        if (action.getId() == ACTION_TMDB_KEY) {
            tmdbApiKey = action.getDescription() != null ? action.getDescription().toString().trim() : "";
        }
        return GuidedAction.ACTION_ID_NEXT;
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        Context context = requireContext();
        long id = action.getId();

        if (id == ACTION_PROVIDER_TVDB || id == ACTION_PROVIDER_TMDB) {
            provider = id == ACTION_PROVIDER_TMDB ? MetadataProvider.TMDB : MetadataProvider.THETVDB;
            RadioActionHelper.enforceExclusivity(getActions(), id,
                    candidateId -> candidateId == ACTION_PROVIDER_TVDB || candidateId == ACTION_PROVIDER_TMDB,
                    this::notifyActionChanged);
        } else if (id == ACTION_PAIR_PHONE) {
            GuidedStepSupportFragment.add(getFragmentManager(), new PhonePairingStepFragment());
        } else if (id == ACTION_TEST_KEY) {
            testKey();
        } else if (id == ACTION_SAVE) {
            Preferences.setMetadataProvider(context, provider);
            Preferences.setTmdbApiKey(context, tmdbApiKey);
            Toast.makeText(context, R.string.metadata_provider_saved, Toast.LENGTH_SHORT).show();
            getFragmentManager().popBackStack();
        }
    }

    private void testKey() {
        Context appContext = requireContext().getApplicationContext();
        String keyToTest = tmdbApiKey;
        new Thread(() -> {
            boolean ok = TmdbClient.testApiKey(keyToTest);
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> Toast.makeText(
                    appContext,
                    ok ? R.string.metadata_tmdb_test_success : R.string.metadata_tmdb_test_failure,
                    Toast.LENGTH_LONG).show());
        }).start();
    }
}
