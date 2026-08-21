package com.hmartinez94.tvrelay;

import android.util.Log;

import org.json.JSONObject;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Lets the user submit a short piece of text (an API key - too painful to
 * type with a D-pad) from their phone's browser instead of the TV's
 * on-screen keyboard. Uses ntfy.sh (https://ntfy.sh), a free, open-source,
 * public pub/sub relay explicitly designed for anonymous ad-hoc topics
 * like this - no account, no signup, on either side. The phone submits to
 * a random, unguessable topic via docs/pair.html (hosted on GitHub Pages -
 * see the repo, must be enabled in Settings -> Pages -> /docs); the TV
 * polls that same topic and picks up the value once it arrives. No
 * backend of our own anywhere in this flow.
 *
 * Uses ntfy's documented single-shot poll endpoint (?poll=1) in a retry
 * loop, rather than its long-lived streaming connection - the poll
 * response shape is well-documented and simple to parse correctly; the
 * streaming connection's exact line-framing behavior wasn't something
 * that could be verified without live-testing against the real service,
 * so the safer, slightly-less-elegant option was chosen deliberately.
 */
final class PhonePairing {

    private static final String TAG = "PhonePairing";

    // Must match wherever docs/pair.html is actually served from.
    private static final String PAIRING_PAGE_URL = "https://hmartinez94.github.io/TVRelay/pair.html";

    private static final long POLL_INTERVAL_MS = 2000;

    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build();

    private PhonePairing() {
    }

    static String newTopic() {
        return "tvrelay-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    static String pairingUrl(String topic) {
        return PAIRING_PAGE_URL + "?topic=" + topic;
    }

    /**
     * Blocks, polling every ~2s, until the phone submits a value or
     * timeoutMs elapses. Call only from a background thread. Returns null
     * on timeout, or if the calling thread is interrupted (used to cancel
     * early if the user backs out of the pairing screen).
     */
    static String awaitValue(String topic, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        // Only messages sent from this exact pairing session should be
        // picked up - the random topic already makes reuse astronomically
        // unlikely, this is just belt-and-suspenders against stale replays.
        long sinceSeconds = System.currentTimeMillis() / 1000;
        String url = "https://ntfy.sh/" + topic + "/json?poll=1&since=" + sinceSeconds;
        Request request = new Request.Builder().url(url).build();

        while (System.currentTimeMillis() < deadline) {
            if (Thread.currentThread().isInterrupted()) {
                return null;
            }
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String body = response.body().string().trim();
                    if (!body.isEmpty()) {
                        // poll=1 can return more than one line if several
                        // messages are queued - only the first matters here.
                        String firstLine = body.split("\n", 2)[0];
                        String message = new JSONObject(firstLine).optString("message", "");
                        if (!message.isEmpty()) {
                            return message;
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Pairing poll attempt failed: " + e.getMessage());
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }
}
