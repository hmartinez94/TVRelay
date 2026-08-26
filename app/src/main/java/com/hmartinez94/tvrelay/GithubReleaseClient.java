package com.hmartinez94.tvrelay;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Checks GitHub's Releases API for a newer TVRelay build than the one
 * installed - see SettingsStepFragment (throttled check + Preferences
 * caching) and ApkInstaller (download + install once one's found). Only
 * meaningful for a sideloaded install; a Play Store install updates through
 * Play instead (see InstallSource.isPlayStoreInstall(), the same gate
 * RestrictedSettingsStepFragment uses for a similar sideload-only concern).
 */
final class GithubReleaseClient {

    private static final String TAG = "GithubReleaseClient";
    private static final String LATEST_RELEASE_URL =
            "https://api.github.com/repos/hmartinez94/TVRelay/releases/latest";
    private static final OkHttpClient httpClient = new OkHttpClient();

    private GithubReleaseClient() {
    }

    /** A GitHub release's version tag and its single .apk asset's download URL. */
    static final class ReleaseInfo {
        final String version;
        final String apkUrl;

        ReleaseInfo(String version, String apkUrl) {
            this.version = version;
            this.apkUrl = apkUrl;
        }
    }

    /**
     * The latest published release, or null if the check failed or the
     * release has no .apk asset. Blocking network call - call only from a
     * background thread.
     */
    static ReleaseInfo fetchLatest() {
        Request request = new Request.Builder().url(LATEST_RELEASE_URL).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                Log.w(TAG, "Releases check failed: " + response.code());
                return null;
            }
            String body = response.body() != null ? response.body().string() : null;
            if (body == null) {
                return null;
            }
            JSONObject json = new JSONObject(body);
            String tag = json.optString("tag_name", "");
            String version = tag.startsWith("v") ? tag.substring(1) : tag;
            if (version.isEmpty()) {
                return null;
            }
            String apkUrl = findApkUrl(json.optJSONArray("assets"));
            return apkUrl != null ? new ReleaseInfo(version, apkUrl) : null;
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Releases check failed", e);
            return null;
        }
    }

    private static String findApkUrl(JSONArray assets) {
        if (assets == null) {
            return null;
        }
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) {
                continue;
            }
            String name = asset.optString("name", "");
            if (name.endsWith(".apk")) {
                String url = asset.optString("browser_download_url", "");
                return !url.isEmpty() ? url : null;
            }
        }
        return null;
    }

    /**
     * Whether `remote` (e.g. "1.4.0") is a newer version than `local`
     * (e.g. Preferences/PackageInfo's plain X.Y.Z versionName - see
     * app/build.gradle.kts). Compares release-numbered segments only, in
     * order, treating a missing/non-numeric segment as 0; returns false
     * (never "newer") on anything unparseable, same "ambiguous -> don't
     * offer it" default InstallSource.isPlayStoreInstall() uses.
     */
    static boolean isNewer(String remote, String local) {
        if (remote == null || remote.isEmpty() || local == null || local.isEmpty()) {
            return false;
        }
        int[] remoteParts = parseVersion(remote);
        int[] localParts = parseVersion(local);
        int length = Math.max(remoteParts.length, localParts.length);
        for (int i = 0; i < length; i++) {
            int r = i < remoteParts.length ? remoteParts[i] : 0;
            int l = i < localParts.length ? localParts[i] : 0;
            if (r != l) {
                return r > l;
            }
        }
        return false;
    }

    private static int[] parseVersion(String version) {
        String[] segments = version.trim().split("\\.");
        int[] parts = new int[segments.length];
        for (int i = 0; i < segments.length; i++) {
            try {
                parts[i] = Integer.parseInt(segments[i].replaceAll("[^0-9]", ""));
            } catch (NumberFormatException e) {
                parts[i] = 0;
            }
        }
        return parts;
    }
}
