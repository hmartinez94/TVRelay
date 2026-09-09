package com.hmartinez94.tvrelay;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import java.util.List;
import java.util.Locale;

/**
 * Gate screen shown before UpdateStepFragment's actual download, from
 * SettingsStepFragment's "Update available" row. Installing an update this
 * way goes through the system's PackageInstaller (session-based install),
 * which re-triggers Android's "Restricted settings" block on the freshly
 * updated package even if Accessibility was already working - confirmed on
 * a real ONN 4K Pro (2026-09-08) via dumpsys package showing
 * installerPackageName=com.google.android.packageinstaller after an
 * in-app update, versus no installer package at all for an ADB install.
 * See RestrictedSettingsStepFragment for the fix once that happens.
 */
public class UpdateWarningStepFragment extends GuidedStepSupportFragment {

    private static final long ACTION_CONTINUE = 1;
    private static final long ACTION_SIDELOAD_INSTEAD = 2;

    @Override
    public GuidanceStylist.Guidance onCreateGuidance(Bundle savedInstanceState) {
        // Guidance's constructor only accepts a plain String for the
        // description (no Spanned support), so this is just a plain
        // concatenation as a fallback; the real, styled text is set in
        // onViewCreated() below by writing directly to the stylist's
        // description TextView, which does accept a Spanned CharSequence.
        return new GuidanceStylist.Guidance(
                getString(R.string.update_warning_title),
                buildDescription().toString(),
                getString(R.string.app_name),
                null);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        TextView descriptionView = getGuidanceStylist().getDescriptionView();
        if (descriptionView != null) {
            descriptionView.setText(buildDescription());
        }
    }

    /**
     * The "will re-trigger Restricted settings" clause is uppercased and
     * colored yellow so it can't be skimmed past (2026-09-08, at explicit
     * user request) - plain-string description text has no way to do this,
     * so the guidance description is built as a Spanned instead. Split into
     * three string resources (prefix/highlight/suffix) rather than marking
     * up one string, since a fixed character offset wouldn't survive
     * translation to Spanish's different sentence order. The joining spaces
     * are added here in code, not left as leading/trailing whitespace in
     * the string resources - aapt trims that whitespace at build time
     * (confirmed real bug, 2026-09-08: the highlighted clause ran straight
     * into the surrounding text with no gap at all), so it has to be
     * literal, non-trimmable code instead.
     */
    private CharSequence buildDescription() {
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        String highlight = getString(R.string.update_warning_highlight).toUpperCase(locale);
        String prefix = getString(R.string.update_warning_prefix);
        SpannableStringBuilder builder = new SpannableStringBuilder()
                .append(prefix)
                .append(" ")
                .append(highlight)
                .append(" ")
                .append(getString(R.string.update_warning_suffix));
        int start = prefix.length() + 1;
        int end = start + highlight.length();
        builder.setSpan(new ForegroundColorSpan(Color.YELLOW), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return builder;
    }

    @Override
    public void onCreateActions(List<GuidedAction> actions, Bundle savedInstanceState) {
        actions.add(new GuidedAction.Builder(requireContext())
                .id(ACTION_CONTINUE)
                .title(getString(R.string.update_warning_continue))
                .build());

        actions.add(new GuidedAction.Builder(requireContext())
                .id(ACTION_SIDELOAD_INSTEAD)
                .title(getString(R.string.update_warning_sideload))
                .build());
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        long id = action.getId();
        if (id == ACTION_CONTINUE) {
            GuidedStepSupportFragment.add(getFragmentManager(), new UpdateStepFragment());
        } else if (id == ACTION_SIDELOAD_INSTEAD) {
            Context context = requireContext().getApplicationContext();
            Toast.makeText(context, R.string.update_warning_sideload_toast, Toast.LENGTH_LONG).show();
            getFragmentManager().popBackStack();
        }
    }
}
