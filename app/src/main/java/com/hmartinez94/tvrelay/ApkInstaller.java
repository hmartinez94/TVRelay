package com.hmartinez94.tvrelay;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Downloads a release APK found by GithubReleaseClient and hands it to
 * Android's package installer - see UpdateStepFragment for the UI around
 * this (permission check first, then download-with-progress, then this
 * class's installApk()).
 */
final class ApkInstaller {

    private static final String TAG = "ApkInstaller";
    private static final OkHttpClient httpClient = new OkHttpClient();
    private static final String APK_FILE_NAME = "update.apk";

    private ApkInstaller() {
    }

    interface ProgressListener {
        void onProgress(int percent);
    }

    /**
     * Whether the OS will let this app's install intent proceed without
     * first sending the user to the "Install unknown apps" system screen -
     * see requestInstallPermissionIntent(). Always true before Android 8
     * (Oreo): that OS version only had a single global "Unknown sources"
     * toggle, which the system's own install dialog already enforces on its
     * own - nothing for this app to check or gate on.
     */
    static boolean canRequestInstall(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return true;
        }
        return context.getPackageManager().canRequestPackageInstalls();
    }

    /**
     * Deep-link to the per-app "Install unknown apps" toggle for this app
     * specifically. Only ever actually called from behind a
     * canRequestInstall() == false check above, which is itself already
     * false on any device below O - but ACTION_MANAGE_UNKNOWN_APP_SOURCES
     * didn't exist before O either way, so this still needs its own
     * annotation for lint's NewApi check to see it's guarded (it doesn't
     * infer that across the caller's separate if-check).
     */
    @RequiresApi(Build.VERSION_CODES.O)
    static Intent requestInstallPermissionIntent(Context context) {
        return new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + context.getPackageName()));
    }

    /**
     * Downloads `apkUrl` into this app's external files dir (no storage
     * permission needed - see file_paths.xml), overwriting any previous
     * download. `listener` is invoked on the calling thread as bytes arrive
     * (0-100); callers on a background thread should hop back to the UI
     * thread themselves, same as GithubReleaseClient.fetchLatest() callers
     * already have to. Blocking network call - call only from a background
     * thread.
     */
    static File download(Context context, String apkUrl, ProgressListener listener) throws IOException {
        File dest = new File(context.getExternalFilesDir(null), APK_FILE_NAME);
        Request request = new Request.Builder().url(apkUrl).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Download failed: " + response.code());
            }
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new IOException("Empty download response");
            }
            long total = responseBody.contentLength();
            try (InputStream in = responseBody.byteStream();
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buffer = new byte[8192];
                long downloaded = 0;
                int lastPercent = -1;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    downloaded += read;
                    if (total > 0 && listener != null) {
                        int percent = (int) (downloaded * 100 / total);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            listener.onProgress(percent);
                        }
                    }
                }
            }
        }
        return dest;
    }

    /**
     * Hands `apkFile` to the system installer via a FileProvider content URI
     * (a plain file:// Uri would violate StrictMode's exposure policy on
     * Android 7+ - see the FileProvider entry in AndroidManifest.xml). The
     * system always shows its own install confirmation UI here regardless
     * of canRequestInstall() above - that final prompt can't be skipped for
     * a non-system app, by design.
     */
    static void installApk(Context context, File apkFile) {
        Uri apkUri = FileProvider.getUriForFile(
                context, context.getPackageName() + ".fileprovider", apkFile);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(apkUri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch installer", e);
        }
    }
}
