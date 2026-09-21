package com.hmartinez94.tvrelay;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import java.util.List;

/**
 * Shared base for the per-group screens opened from SettingsStepFragment's
 * main menu (Detection & Matching, Confirmation Overlay, YouTube Redirect,
 * Accessibility & Permissions, More).
 *
 * Owns the one piece of behavior every one of them needs identically: the
 * onResume() rebuild that picks up state changed while the screen was away
 * (an overlay/accessibility grant made in system Settings, an OCR consent
 * screen, the metadata provider picked on a nested screen), and the skip of
 * that rebuild on the very first onResume() - the confirmed 2026-08-27 bug
 * where a redundant rebuild right after onCreateActions() could replace the
 * list's views out from under a fast first click. Kept here so it exists once
 * instead of being reimplemented (or forgotten) per screen.
 */
abstract class SettingsSubStepFragment extends GuidedStepSupportFragment {

    /** See onResume() - skips its rebuild on the very first call. */
    private boolean firstResume = true;

    /** Screen title - normally the same string the main-menu row uses. */
    protected abstract int getTitleRes();

    /** One short line under the title. */
    protected abstract int getDescriptionRes();

    /** Builds the rows from current Preferences/system state; called on create and on every later resume. */
    protected abstract List<GuidedAction> buildActions(Context context);

    @Override
    public GuidanceStylist.Guidance onCreateGuidance(Bundle savedInstanceState) {
        return new GuidanceStylist.Guidance(
                getString(getTitleRes()),
                getString(getDescriptionRes()),
                getString(R.string.app_name),
                null);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = super.onCreateView(inflater, container, savedInstanceState);
        // Rebuilt lists must re-bind rows to their new action objects - see ActionListDiff.
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
        if (!firstResume) {
            setActions(buildActions(requireContext()));
        }
        firstResume = false;
    }
}
