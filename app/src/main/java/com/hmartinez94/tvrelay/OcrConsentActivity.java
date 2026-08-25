package com.hmartinez94.tvrelay;

import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.util.Log;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

/**
 * Transparent, excludeFromRecents, single-purpose host for the one-time
 * MediaProjection screen-capture consent dialog. Exists purely because,
 * since Android 10, MediaProjectionManager.createScreenCaptureIntent()'s
 * system dialog can only be launched from an Activity context - not
 * directly from OcrCaptureManager's caller (the AccessibilityService) or
 * from OcrCaptureForegroundService (a background Service).
 *
 * Started with FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NO_ANIMATION by
 * OcrCaptureManager.requestTitleCapture() the first time a capture is
 * needed (or after a prior session has been torn down); reports the
 * result back via the static OcrCaptureManager.notifyConsentResult() hook
 * (see that class's javadoc for why a static single-slot callback is used
 * here instead of a normal instance callback) and finishes itself
 * immediately either way - there is nothing else for this activity to show
 * or do.
 */
public final class OcrConsentActivity extends ComponentActivity {

    private static final String TAG = "OcrConsentActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ActivityResultLauncher<Intent> launcher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), this::onConsentResult);

        MediaProjectionManager projectionManager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (projectionManager == null) {
            Log.e(TAG, "MediaProjectionManager unavailable - cannot request capture consent");
            OcrCaptureManager.notifyConsentResult(false, RESULT_CANCELED, null);
            finish();
            return;
        }

        launcher.launch(projectionManager.createScreenCaptureIntent());
    }

    private void onConsentResult(ActivityResult result) {
        Intent data = result.getData();
        if (result.getResultCode() == RESULT_OK && data != null) {
            Log.d(TAG, "MediaProjection consent granted");
            Intent serviceIntent = new Intent(this, OcrCaptureForegroundService.class);
            serviceIntent.putExtra(OcrCaptureForegroundService.EXTRA_RESULT_CODE, result.getResultCode());
            serviceIntent.putExtra(OcrCaptureForegroundService.EXTRA_RESULT_DATA, data);
            ContextCompat.startForegroundService(this, serviceIntent);
            OcrCaptureManager.notifyConsentResult(true, result.getResultCode(), data);
        } else {
            Log.d(TAG, "MediaProjection consent denied or cancelled");
            OcrCaptureManager.notifyConsentResult(false, result.getResultCode(), null);
        }
        finish();
    }
}
