package com.hmartinez94.tvrelay;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Toast;

import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import java.util.List;

/**
 * Kodi host/port/credentials, entered as inline-editable GuidedActions
 * (the standard Leanback pattern for D-pad-friendly text entry).
 */
public class KodiSettingsStepFragment extends GuidedStepSupportFragment {

    private static final long ACTION_HOST = 1;
    private static final long ACTION_PORT = 2;
    private static final long ACTION_USER = 3;
    private static final long ACTION_PASSWORD = 4;
    private static final long ACTION_TEST = 5;
    private static final long ACTION_SAVE = 6;

    private static final String PASSWORD_MASK = "••••••••";

    private String host;
    private int port;
    private String user;
    private String password;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Load the current values BEFORE calling super: GuidedStepSupportFragment.onCreate()
        // invokes onCreateActions() internally, so anything it reads (host/port/user/password)
        // must already be set by the time super.onCreate() runs, not after.
        Context context = requireContext();
        host = Preferences.getKodiHost(context);
        port = Preferences.getKodiPort(context);
        user = Preferences.getKodiUser(context);
        password = Preferences.getKodiPassword(context);
        super.onCreate(savedInstanceState);
    }

    @Override
    public GuidanceStylist.Guidance onCreateGuidance(Bundle savedInstanceState) {
        return new GuidanceStylist.Guidance(
                getString(R.string.kodi_settings_title),
                getString(R.string.kodi_settings_description),
                getString(R.string.app_name),
                null);
    }

    @Override
    public void onCreateActions(List<GuidedAction> actions, Bundle savedInstanceState) {
        Context context = requireContext();

        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_HOST)
                .title(getString(R.string.kodi_field_host))
                .description(host)
                .descriptionEditable(true)
                .descriptionEditInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
                .build());

        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_PORT)
                .title(getString(R.string.kodi_field_port))
                .description(String.valueOf(port))
                .descriptionEditable(true)
                .descriptionEditInputType(InputType.TYPE_CLASS_NUMBER)
                .build());

        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_USER)
                .title(getString(R.string.kodi_field_username))
                .description(user)
                .descriptionEditable(true)
                .descriptionEditInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
                .build());

        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_PASSWORD)
                .title(getString(R.string.kodi_field_password))
                .description(password.isEmpty() ? "" : PASSWORD_MASK)
                .descriptionEditable(true)
                .descriptionEditInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD)
                .build());

        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_TEST)
                .title(getString(R.string.kodi_action_test))
                .build());

        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_SAVE)
                .title(getString(R.string.kodi_action_save))
                .build());
    }

    @Override
    public long onGuidedActionEditedAndProceed(GuidedAction action) {
        String value = action.getDescription() != null ? action.getDescription().toString() : "";
        long id = action.getId();
        if (id == ACTION_HOST) {
            host = value;
        } else if (id == ACTION_PORT) {
            try {
                port = Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                port = Preferences.getKodiPort(requireContext());
            }
        } else if (id == ACTION_USER) {
            user = value;
        } else if (id == ACTION_PASSWORD) {
            // Only overwrite the stored password if the user actually typed
            // something new - otherwise tabbing past the masked placeholder
            // without editing it would clobber the real saved password.
            if (!PASSWORD_MASK.equals(value)) {
                password = value;
            }
        }
        return GuidedAction.ACTION_ID_NEXT;
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        Context context = requireContext();
        if (action.getId() == ACTION_TEST) {
            testConnection();
        } else if (action.getId() == ACTION_SAVE) {
            Preferences.setKodiConnection(context, host, port, user, password);
            Toast.makeText(context, R.string.kodi_saved, Toast.LENGTH_SHORT).show();
            getFragmentManager().popBackStack();
        }
    }

    private void testConnection() {
        Context appContext = requireContext().getApplicationContext();
        // Save the in-progress values first so the test call - which reads
        // from Preferences, same as the accessibility service does - checks
        // exactly what's on screen right now, not the last saved values.
        Preferences.setKodiConnection(appContext, host, port, user, password);
        new Thread(() -> {
            boolean ok = KodiClient.testConnection(appContext);
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> Toast.makeText(
                    appContext,
                    ok ? R.string.kodi_test_success : R.string.kodi_test_failure,
                    Toast.LENGTH_LONG).show());
        }).start();
    }
}
