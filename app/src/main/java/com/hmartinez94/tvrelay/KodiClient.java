package com.hmartinez94.tvrelay;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Talks to Kodi's JSON-RPC control interface (Kodi: Settings -> Services ->
 * Control -> "Allow remote control via HTTP") to open a resolved title's
 * info page.
 *
 * Kodi's Android manifest (checked directly against xbmc/xbmc source)
 * accepts ACTION_VIEW for http/https/file/smb/ftp/rtsp/... but NOT a
 * plugin:// or custom URI scheme, so unlike Nuvio/Stremio there is no
 * simple deep link into Kodi. Kodi normally runs on the same device as
 * this app, so JSON-RPC over http://127.0.0.1:{port} is the way in -
 * though host/port/credentials are user-configurable in Settings in case
 * Kodi runs on a separate box on the LAN.
 *
 * IMPORTANT: the method/param shapes below are Kodi's documented
 * JSON-RPC v13 API but were NOT confirmed against a live device while
 * writing this - kodi.wiki blocked automated fetching during planning, so
 * this is the one piece of the app built from general API knowledge
 * rather than a verified spec. Test end-to-end on a real Kodi install
 * (plan verification step 6) before relying on it; if it proves flaky,
 * the settings screen should let Kodi be deselected in favor of
 * Nuvio/Stremio without code changes.
 */
final class KodiClient {

    private static final String TAG = "KodiClient";
    private static final okhttp3.MediaType JSON = okhttp3.MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build();

    enum Result {
        OPENED,
        NOT_FOUND,
        CONNECTION_FAILED
    }

    private KodiClient() {
    }

    /** Blocking network call(s) - call only from a background thread. */
    static Result open(Context context, TvdbMatch match) {
        try {
            if (match.getType() == MediaType.MOVIE) {
                Integer movieId = findId(context, "VideoLibrary.GetMovies", "movies", "movieid", match.getImdbId());
                if (movieId == null) {
                    return Result.NOT_FOUND;
                }
                return activate(context, "videodb://movies/titles/" + movieId + "/");
            } else {
                Integer showId = findId(context, "VideoLibrary.GetTVShows", "tvshows", "tvshowid", match.getImdbId());
                if (showId == null) {
                    return Result.NOT_FOUND;
                }
                return activate(context, "videodb://tvshows/titles/" + showId + "/");
            }
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Could not reach Kodi", e);
            return Result.CONNECTION_FAILED;
        }
    }

    /** Reachability/credentials check for the Settings "Test connection" action. */
    static boolean testConnection(Context context) {
        try {
            JSONObject response = call(context, "JSONRPC.Ping", null);
            return response != null && "pong".equals(response.optString("result", ""));
        } catch (Exception e) {
            Log.w(TAG, "Kodi connection test failed", e);
            return false;
        }
    }

    private static Integer findId(Context context, String method, String listKey, String idKey, String imdbId)
            throws IOException, JSONException {
        JSONObject params = new JSONObject();
        JSONArray properties = new JSONArray();
        properties.put("imdbnumber");
        params.put("properties", properties);

        JSONObject result = call(context, method, params);
        if (result == null) {
            return null;
        }
        JSONArray items = result.optJSONArray(listKey);
        if (items == null) {
            return null;
        }
        // Matched client-side against the full list rather than a
        // server-side filter: Kodi's VideoLibrary filter parameter doesn't
        // reliably support arbitrary fields like imdbnumber across
        // versions. Fine for a typical personal library.
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (imdbId.equals(item.optString("imdbnumber", ""))) {
                return item.getInt(idKey);
            }
        }
        return null;
    }

    private static Result activate(Context context, String videoDbPath) throws IOException, JSONException {
        JSONObject params = new JSONObject();
        params.put("window", "videos");
        JSONArray parameters = new JSONArray();
        parameters.put(videoDbPath);
        params.put("parameters", parameters);

        JSONObject result = call(context, "GUI.ActivateWindow", params);
        return result != null ? Result.OPENED : Result.CONNECTION_FAILED;
    }

    private static JSONObject call(Context context, String method, JSONObject params) throws IOException, JSONException {
        JSONObject payload = new JSONObject();
        payload.put("jsonrpc", "2.0");
        payload.put("method", method);
        if (params != null) {
            payload.put("params", params);
        }
        payload.put("id", 1);

        String host = Preferences.getKodiHost(context);
        int port = Preferences.getKodiPort(context);
        String user = Preferences.getKodiUser(context);
        String password = Preferences.getKodiPassword(context);

        Request.Builder builder = new Request.Builder()
                .url("http://" + host + ":" + port + "/jsonrpc")
                .post(RequestBody.create(payload.toString(), JSON));
        if (!user.isEmpty()) {
            builder.header("Authorization", Credentials.basic(user, password));
        }

        try (Response response = httpClient.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                Log.w(TAG, "Kodi returned " + response.code() + " for " + method);
                return null;
            }
            String body = response.body() != null ? response.body().string() : null;
            if (body == null) {
                return null;
            }
            JSONObject json = new JSONObject(body);
            if (json.has("error")) {
                Log.w(TAG, "Kodi error for " + method + ": " + json.optJSONObject("error"));
                return null;
            }
            Object result = json.opt("result");
            if (result instanceof JSONObject) {
                return (JSONObject) result;
            }
            // Some methods (JSONRPC.Ping -> "pong", GUI.ActivateWindow ->
            // "OK") return a bare primitive as `result`, not an object.
            // Wrap it under the same "result" key so callers have one
            // shape to check either way.
            JSONObject wrapped = new JSONObject();
            wrapped.put("result", result);
            return wrapped;
        }
    }
}
