package com.hmartinez94.tvrelay;

import android.util.Log;

import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
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

    /**
     * @param label shown on the phone-side page so the user can tell which
     *              field they're about to send (this screen is now shared by
     *              the TMDB key, and the Jellyfin URL/API key - see
     *              PhonePairingStepFragment's static factories). Passed
     *              through raw (URL-encoded below); pair.html falls back to
     *              a generic prompt if it's missing entirely.
     */
    static String pairingUrl(String topic, String label) {
        try {
            return PAIRING_PAGE_URL + "?topic=" + topic + "&label=" + URLEncoder.encode(label, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            // UTF-8 is always supported - unreachable in practice.
            return PAIRING_PAGE_URL + "?topic=" + topic;
        }
    }

    /**
     * Lets a caller abort an in-progress awaitValue() immediately - see
     * cancel(). Confirmed real bug (2026-08-27): awaitValue() previously had
     * no way to be stopped early at all beyond the calling Thread's own
     * interrupt state, which nothing ever set - backing out of a pairing
     * screen (Cancel, BACK, or just navigating away) left its polling loop
     * running for the rest of its full 5-minute window regardless. Multiple
     * abandoned attempts piling up (observed live: 5 concurrent polling
     * threads from 5 separate pairing attempts, all still retrying) was
     * enough to make a brand-new attempt starve: PhonePairing's single
     * shared OkHttpClient caps concurrent requests per host at 5 by
     * default, so a 6th request just queues behind the dead ones instead of
     * ever actually being sent - "worked the first time, the rest didn't"
     * was this, not a per-attempt reachability problem.
     */
    static final class Session {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile Call currentCall;

        boolean isCancelled() {
            return cancelled.get();
        }

        /**
         * Stops any further poll attempts and aborts one already in flight,
         * if there is one - Call.cancel() closes the underlying socket
         * immediately, unlike Thread.interrupt(), which OkHttp's blocking
         * execute() doesn't reliably respond to mid-connect. Safe to call
         * from any thread (currentCall is only ever read/written via this
         * volatile field, never synchronized elsewhere) and more than once.
         */
        void cancel() {
            cancelled.set(true);
            Call call = currentCall;
            if (call != null) {
                call.cancel();
            }
        }
    }

    /**
     * Blocks, polling every ~2s, until the phone submits a value, timeoutMs
     * elapses, or session.cancel() is called from another thread. Call only
     * from a background thread. Returns null on timeout or cancellation.
     */
    static String awaitValue(String topic, long timeoutMs, Session session) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        // No `since` filter: an earlier version scoped this to
        // "since=<TV's own clock at pairing start>", meant as belt-and-
        // suspenders against stale replays (the random topic already makes
        // that astronomically unlikely on its own). Confirmed real bug
        // (2026-08-27): ntfy compares `since` against its own server-time
        // for each message, not the TV's - a TV whose system clock is even
        // a little fast (common on cheap Android TV boxes without reliable
        // NTP sync) would set a `since` in ntfy's future, so a message the
        // phone genuinely sent could be silently filtered out of every poll
        // for the rest of the 5-minute wait, indistinguishable from the
        // phone never having sent anything at all. The topic being a fresh,
        // random, single-use value each time this screen opens is already
        // sufficient protection against seeing a stale message from a
        // previous pairing - don't re-add a `since` filter without a fix
        // for the clock-skew failure mode this caused.
        String url = "https://ntfy.sh/" + topic + "/json?poll=1";
        Request request = new Request.Builder().url(url).build();

        while (System.currentTimeMillis() < deadline) {
            if (session.isCancelled() || Thread.currentThread().isInterrupted()) {
                return null;
            }
            Call call = httpClient.newCall(request);
            session.currentCall = call;
            try (Response response = call.execute()) {
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
                // Also fires when cancel() aborts this exact call
                // (call.cancel() surfaces as an IOException here) - the
                // isCancelled() check right after distinguishes that from a
                // genuine network failure worth retrying.
                Log.w(TAG, "Pairing poll attempt failed: " + e.getMessage());
            } finally {
                session.currentCall = null;
            }
            if (session.isCancelled()) {
                return null;
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
