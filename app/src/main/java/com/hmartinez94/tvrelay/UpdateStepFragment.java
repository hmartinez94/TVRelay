package com.hmartinez94.tvrelay;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Reached from SettingsStepFragment's "Update available" row once
 * GithubReleaseClient has found a newer release than BuildConfig.VERSION_NAME.
 * Downloads the release's .apk (ApkInstaller.download) and hands it to the
 * system installer (ApkInstaller.installApk) - sideload-only, gated behind
 * InstallSource.isPlayStoreInstall() one level up in SettingsStepFragment,
 * the same gate RestrictedSettingsStepFragment uses for a similar
 * sideload-only concern.
 */
public class UpdateStepFragment extends GuidedStepSupportFragment {

    private static final long ACTION_DOWNLOAD = 1;
    private static final long ACTION_CANCEL = 2;

    private String latestVersion;
    private String apkUrl;
    private boolean downloading;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Load before super.onCreate() - see MetadataProviderStepFragment's
        // onCreate() for why (onCreateActions() runs inside it).
        Context context = requireContext();
        latestVersion = Preferences.getUpdateLatestVersion(context);
        apkUrl = Preferences.getUpdateApkUrl(context);
        super.onCreate(savedInstanceState);
    }

    @Override
    public GuidanceStylist.Guidance onCreateGuidance(Bundle savedInstanceState) {
        return new GuidanceStylist.Guidance(
                getString(R.string.update_title),
                getString(R.string.update_description, latestVersion, BuildConfig.VERSION_NAME),
                getString(R.string.app_name),
                null);
    }

    @Override
    public void onCreateActions(List<GuidedAction> actions, Bundle savedInstanceState) {
        Context context = requireContext();
        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_DOWNLOAD)
                .title(getString(R.string.update_download))
                .description(downloadRowDescription(context))
                .build());
        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_CANCEL)
                .title(getString(R.string.update_cancel))
                .build());
    }

    private String downloadRowDescription(Context context) {
        return ApkInstaller.canRequestInstall(context) ? null : getString(R.string.update_permission_needed);
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        Context context = requireContext();
        long id = action.getId();

        if (id == ACTION_CANCEL) {
            getFragmentManager().popBackStack();
            return;
        }

        if (id == ACTION_DOWNLOAD) {
            if (downloading || apkUrl.isEmpty()) {
                return;
            }
            // canRequestInstall() is unconditionally true below API 26 (see
            // its doc), so reaching here means SDK_INT >= O already - this
            // redundant inline check just keeps that guarantee local and
            // explicit for lint's NewApi detector, which doesn't infer
            // safety across a separate helper method's own internal check.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ApkInstaller.canRequestInstall(context)) {
                startActivity(ApkInstaller.requestInstallPermissionIntent(context));
                return;
            }
            startDownload(action);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // May be returning from the "Install unknown apps" system screen or
        // the installer itself (see startDownload()/onGuidedActionClicked()
        // above) - refresh the row to reflect current permission state.
        // Skipped while an actual download is in flight, so a screen-off/on
        // cycle mid-download can't stomp on it - same "picked something up
        // elsewhere, refresh on return" shape MetadataProviderStepFragment's
        // onResume() uses for phone pairing, just guarded for this screen's
        // extra in-flight-download case.
        if (downloading) {
            return;
        }
        GuidedAction downloadAction = findActionById(ACTION_DOWNLOAD);
        if (downloadAction != null) {
            downloadAction.setDescription(downloadRowDescription(requireContext()));
            notifyActionChanged(getActions().indexOf(downloadAction));
        }
    }

    private void startDownload(GuidedAction action) {
        Context appContext = requireContext().getApplicationContext();
        downloading = true;
        action.setDescription(getString(R.string.update_downloading, 0));
        notifyActionChanged(getActions().indexOf(action));

        new Thread(() -> {
            try {
                File apk = ApkInstaller.download(appContext, apkUrl, percent -> onProgress(action, percent));
                if (!isAdded()) {
                    return;
                }
                requireActivity().runOnUiThread(() -> {
                    // Reset before installApk() launches the installer
                    // Activity (which pauses this fragment) - by the time
                    // onResume() above runs again, this background thread
                    // has definitely finished either way.
                    downloading = false;
                    ApkInstaller.installApk(appContext, apk);
                });
            } catch (IOException e) {
                if (!isAdded()) {
                    return;
                }
                requireActivity().runOnUiThread(() -> {
                    downloading = false;
                    Toast.makeText(appContext, R.string.update_download_failed, Toast.LENGTH_LONG).show();
                    action.setDescription(downloadRowDescription(appContext));
                    notifyActionChanged(getActions().indexOf(action));
                });
            }
        }).start();
    }

    private void onProgress(GuidedAction action, int percent) {
        if (!isAdded()) {
            return;
        }
        requireActivity().runOnUiThread(() -> {
            action.setDescription(getString(R.string.update_downloading, percent));
            notifyActionChanged(getActions().indexOf(action));
        });
    }
}
