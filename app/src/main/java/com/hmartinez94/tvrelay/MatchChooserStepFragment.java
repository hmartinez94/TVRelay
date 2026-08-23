package com.hmartinez94.tvrelay;

import android.content.Context;
import android.os.Bundle;
import android.widget.Toast;

import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Candidate chooser for the manual-search path (SearchStepFragment). Uses a
 * plain leanback list rather than MatchTrayView's floating tray: unlike the
 * accessibility-service launcher-click path, the user is already inside
 * TVRelay here, so a native-looking list costs no extra screen transition -
 * see MatchChooserActivity/MatchTrayOverlay for the launcher-click hosts,
 * which is exactly the tradeoff the floating tray exists to avoid there.
 */
public class MatchChooserStepFragment extends GuidedStepSupportFragment {

    private static final String ARG_QUERY_TITLE = "query_title";
    private static final String ARG_CANDIDATES = "candidates";

    private static final long ACTION_CANCEL = 1;
    private static final long ACTION_CANDIDATE_BASE = 10;

    private String queryTitle;
    private List<TitleCandidate> candidates;

    static MatchChooserStepFragment create(String queryTitle, List<TitleCandidate> candidates) {
        MatchChooserStepFragment fragment = new MatchChooserStepFragment();
        Bundle args = new Bundle();
        args.putString(ARG_QUERY_TITLE, queryTitle);
        args.putSerializable(ARG_CANDIDATES, new ArrayList<>(candidates));
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Loaded before super.onCreate(): GuidedStepSupportFragment.onCreate()
        // calls onCreateActions() internally, so fields it reads must
        // already be set - see MetadataProviderStepFragment for the crash
        // this ordering avoids.
        Bundle args = getArguments();
        queryTitle = args.getString(ARG_QUERY_TITLE, "");
        candidates = readCandidates(args);
        super.onCreate(savedInstanceState);
    }

    @SuppressWarnings("unchecked")
    private static List<TitleCandidate> readCandidates(Bundle args) {
        Serializable extra = args.getSerializable(ARG_CANDIDATES);
        return extra instanceof List ? (List<TitleCandidate>) extra : new ArrayList<>();
    }

    @Override
    public GuidanceStylist.Guidance onCreateGuidance(Bundle savedInstanceState) {
        return new GuidanceStylist.Guidance(
                getString(R.string.chooser_heading, queryTitle),
                getString(R.string.chooser_description),
                getString(R.string.app_name),
                null);
    }

    @Override
    public void onCreateActions(List<GuidedAction> actions, Bundle savedInstanceState) {
        Context context = requireContext();
        for (int i = 0; i < candidates.size(); i++) {
            TitleCandidate candidate = candidates.get(i);
            String year = candidate.year != Integer.MIN_VALUE
                    ? String.valueOf(candidate.year)
                    : getString(R.string.chooser_unknown_year);
            String type = getString(candidate.type == MediaType.MOVIE
                    ? R.string.chooser_type_movie
                    : R.string.chooser_type_series);
            String description = year + " · " + type;
            if (candidate.akaTitle != null) {
                description += " · " + getString(R.string.chooser_aka, candidate.akaTitle);
            }
            actions.add(new GuidedAction.Builder(context)
                    .id(ACTION_CANDIDATE_BASE + i)
                    .title(candidate.displayTitle)
                    .description(description)
                    .build());
        }
        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_CANCEL)
                .title(getString(R.string.chooser_cancel))
                .build());
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        long id = action.getId();
        if (id == ACTION_CANCEL) {
            getFragmentManager().popBackStack();
            return;
        }
        int index = (int) (id - ACTION_CANDIDATE_BASE);
        if (index < 0 || index >= candidates.size()) {
            return;
        }
        resolveAndOpen(candidates.get(index));
    }

    private void resolveAndOpen(TitleCandidate candidate) {
        Context appContext = requireContext().getApplicationContext();
        new Thread(() -> {
            boolean opened = PlayerLauncher.openCandidate(appContext, candidate);
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                if (!opened) {
                    Toast.makeText(appContext, R.string.search_not_found, Toast.LENGTH_LONG).show();
                    return;
                }
                requireActivity().finish();
            });
        }).start();
    }
}
