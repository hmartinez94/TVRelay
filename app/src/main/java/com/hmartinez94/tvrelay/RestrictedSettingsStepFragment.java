package com.hmartinez94.tvrelay;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.TextView;

import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import java.util.List;

/**
 * Interactive walkthrough offered when SettingsStepFragment infers the user
 * is stuck on Android 13+'s "Restricted settings" block (a sideload-install
 * protection that can leave the accessibility service toggle greyed out -
 * see the static explanation already in about_restricted_settings). This
 * screen deliberately only ever calls
 * startActivity() with documented public Settings intents, or reads
 * read-only state - it never writes Settings.Secure directly and never
 * calls AppOpsManager to grant anything, since WRITE_SECURE_SETTINGS is
 * signature/system-only and unobtainable by a normal app. This follows the
 * same pattern this project uses everywhere else it runs into an
 * OS-enforced restriction (a sideloaded, non-isAccessibilityTool service
 * cannot retrieve window content or take a screenshot on this device,
 * regardless of manifest flags):
 * document it honestly and offer only what's actually possible, rather than
 * pretend a bypass exists.
 *
 * The ADB command lives in the guidance (left info panel) text, not as a
 * clickable action - it isn't something a click can do anything with (see
 * onGuidedActionClicked's absence of a case for it), it's information to
 * read, so it belongs with the rest of the explanation rather than looking
 * like a button (2026-09-08, at explicit user request, reversing an earlier
 * attempt that put it in its own action row).
 *
 * That panel's description TextView clips at a fixed ~5 lines by default
 * (confirmed on a real ONN 4K Pro, 2026-09-08: a paragraph of that length
 * got cut with "..." mid-sentence) - rather than continuing to fight that
 * cap with ever-shorter copy, onViewCreated() below removes it directly
 * (setMaxLines/setEllipsize) and re-sets the description as a Spanned so
 * the command can be colored. GuidanceStylist.Guidance's constructor only
 * accepts a plain String (confirmed by a failed compile, see
 * UpdateWarningStepFragment for the same finding) - onCreateGuidance()
 * below passes an unstyled fallback that this immediately overwrites.
 *
 * The QR code (linking to docs/index.html's #adb-accessibility section, the
 * same content this whole screen is a condensed version of) reuses
 * QrCodeGenerator/the Guidance-icon-slot pattern from PhonePairingStepFragment.
 */
public class RestrictedSettingsStepFragment extends GuidedStepSupportFragment {

    private static final String HELP_PAGE_URL = "https://hmartinez94.github.io/TVRelay/#adb-accessibility";
    private static final int QR_SIZE_PX = 400;

    private static final long ACTION_RETRY_ACCESSIBILITY = 1;
    private static final long ACTION_OPEN_APP_INFO = 2;
    private static final long ACTION_CLOSE = 3;

    @Override
    public GuidanceStylist.Guidance onCreateGuidance(Bundle savedInstanceState) {
        Bitmap qr = QrCodeGenerator.generate(HELP_PAGE_URL, QR_SIZE_PX);
        Drawable icon = qr != null ? new BitmapDrawable(getResources(), qr) : null;
        return new GuidanceStylist.Guidance(
                getString(R.string.restricted_settings_help_title),
                buildDescription().toString(),
                getString(R.string.app_name),
                icon);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        TextView descriptionView = getGuidanceStylist().getDescriptionView();
        if (descriptionView != null) {
            descriptionView.setMaxLines(Integer.MAX_VALUE);
            descriptionView.setEllipsize(null);
            descriptionView.setText(buildDescription());
        }
    }

    private CharSequence buildDescription() {
        SpannableStringBuilder builder = new SpannableStringBuilder()
                .append(getString(R.string.restricted_settings_help_intro))
                .append(" ")
                .append(getString(R.string.restricted_settings_adb_fix))
                .append("\n");
        int start = builder.length();
        builder.append(getString(R.string.restricted_settings_adb_fix_command));
        int end = builder.length();
        builder.setSpan(new ForegroundColorSpan(Color.YELLOW), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.append("\n\n").append(getString(R.string.restricted_settings_scan_more));
        return builder;
    }

    @Override
    public void onCreateActions(List<GuidedAction> actions, Bundle savedInstanceState) {
        actions.add(new GuidedAction.Builder(requireContext())
                .id(ACTION_RETRY_ACCESSIBILITY)
                .title(getString(R.string.restricted_settings_retry_accessibility))
                .build());

        actions.add(new GuidedAction.Builder(requireContext())
                .id(ACTION_OPEN_APP_INFO)
                .title(getString(R.string.restricted_settings_open_app_info))
                .description(getString(R.string.restricted_settings_open_app_info_description))
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
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } else if (id == ACTION_CLOSE) {
            getFragmentManager().popBackStack();
        }
    }
}
