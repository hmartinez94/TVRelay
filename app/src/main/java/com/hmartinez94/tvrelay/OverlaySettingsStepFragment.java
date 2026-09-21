package com.hmartinez94.tvrelay;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;

import androidx.leanback.widget.GuidedAction;
import androidx.leanback.widget.GuidedActionsStylist;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Confirmation Overlay: the "Watch now" button and how it behaves once
 * dismissed. Rows depend on each other (Reappear needs the overlay
 * permission; the delay and long-press rows need Reappear), so toggles patch
 * the dependent rows in place with findActionById()/notifyActionChanged()
 * rather than a full setActions() rebuild, which would reset D-pad focus to
 * the top of the list.
 *
 * The Reappear delay row is adjusted with D-pad Left/Right (see
 * onCreateActionsStylist()), with a plain select press stepping forward
 * (wrapping) as a fallback so the value stays reachable if key interception
 * ever misbehaves on some launcher/firmware.
 */
public class OverlaySettingsStepFragment extends SettingsSubStepFragment {

    private static final long ACTION_ENABLE_OVERLAY = 1;
    private static final long ACTION_OVERLAY_REAPPEAR = 2;
    private static final long ACTION_REAPPEAR_DELAY = 3;
    private static final long ACTION_OVERLAY_LONG_PRESS = 4;

    private final View.OnKeyListener delayKeyListener = (v, keyCode, event) -> {
        if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) {
            return false;
        }
        GuidedAction delayAction = findActionById(ACTION_REAPPEAR_DELAY);
        if (delayAction == null || !delayAction.isEnabled()) {
            // Disabled row: leave Left/Right to normal focus handling.
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            // Key repeat is allowed on purpose: holding Right walks the grid.
            stepDelay(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1, false);
        }
        return true;
    };

    @Override
    protected int getTitleRes() {
        return R.string.settings_section_overlay;
    }

    @Override
    protected int getDescriptionRes() {
        return R.string.settings_group_overlay_description;
    }

    @Override
    protected List<GuidedAction> buildActions(Context context) {
        List<GuidedAction> actions = new ArrayList<>();

        boolean overlayGranted = Settings.canDrawOverlays(context);
        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_ENABLE_OVERLAY)
                .title(getString(R.string.settings_enable_overlay))
                .description(getString(overlayGranted
                        ? R.string.settings_overlay_status_enabled
                        : R.string.settings_overlay_status_disabled))
                .build());

        // Reappear is meaningless without the overlay permission - hide()
        // never even gets a button to conceal/reveal without it (see
        // WatchNowOverlay.ensureButton()) - so the row is disabled (not just
        // hidden, so its own default-on state stays visible/predictable)
        // until overlayGranted is true.
        boolean reappearEnabled = Preferences.isOverlayReappearEnabled(context);
        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_OVERLAY_REAPPEAR)
                .title(getString(R.string.settings_overlay_reappear))
                .description(getString(!overlayGranted
                        ? R.string.settings_overlay_reappear_status_requires_permission
                        : reappearEnabled
                                ? R.string.settings_overlay_reappear_status_enabled
                                : R.string.settings_overlay_reappear_status_disabled))
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(reappearEnabled)
                .enabled(overlayGranted)
                .build());

        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_REAPPEAR_DELAY)
                .title(getString(R.string.settings_overlay_reappear_delay))
                .description(delayDescription(context, overlayGranted, reappearEnabled))
                .enabled(overlayGranted && reappearEnabled)
                .build());

        // Long-press-only's short-press dismiss (see WatchNowOverlay.hide())
        // only does anything when Reappear is on - see
        // Preferences.isOverlayLongPressOnlyReady().
        boolean longPressOnly = Preferences.isOverlayLongPressOnlyEnabled(context);
        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_OVERLAY_LONG_PRESS)
                .title(getString(R.string.settings_overlay_long_press))
                .description(longPressDescription(context, overlayGranted, reappearEnabled))
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(longPressOnly)
                .enabled(overlayGranted && reappearEnabled)
                .build());
        return actions;
    }

    private String delayDescription(Context context, boolean overlayGranted, boolean reappearEnabled) {
        if (!overlayGranted) {
            return getString(R.string.settings_overlay_reappear_delay_requires_permission);
        }
        if (!reappearEnabled) {
            return getString(R.string.settings_overlay_reappear_delay_requires_reappear);
        }
        return getString(R.string.settings_overlay_reappear_delay_description,
                String.format(Locale.getDefault(), "%.1f",
                        Preferences.getOverlayReappearDelayMs(context) / 1000f));
    }

    private String longPressDescription(Context context, boolean overlayGranted, boolean reappearEnabled) {
        return getString(!overlayGranted
                ? R.string.settings_overlay_long_press_status_requires_permission
                : !reappearEnabled
                        ? R.string.settings_overlay_long_press_status_requires_reappear
                        : Preferences.isOverlayLongPressOnlyEnabled(context)
                                ? R.string.settings_overlay_long_press_status_enabled
                                : R.string.settings_overlay_long_press_status_disabled);
    }

    /**
     * Steps the stored delay one grid position and refreshes only that row.
     * With wrap, going past the maximum lands back on the minimum - used by
     * the select-press fallback; the Left/Right path clamps at both ends.
     */
    private void stepDelay(int direction, boolean wrap) {
        Context context = requireContext();
        long current = Preferences.getOverlayReappearDelayMs(context);
        long next = Preferences.stepOverlayReappearDelay(current, direction);
        if (wrap && direction > 0 && current >= Preferences.OVERLAY_REAPPEAR_DELAY_MAX_MS) {
            next = Preferences.OVERLAY_REAPPEAR_DELAY_MIN_MS;
        }
        if (next == current) {
            return;
        }
        Preferences.setOverlayReappearDelayMs(context, next);
        GuidedAction delayAction = findActionById(ACTION_REAPPEAR_DELAY);
        if (delayAction != null) {
            delayAction.setDescription(delayDescription(context, true, true));
            notifyActionChanged(getActions().indexOf(delayAction));
        }
    }

    @Override
    public GuidedActionsStylist onCreateActionsStylist() {
        return new GuidedActionsStylist() {
            @Override
            public void onBindViewHolder(ViewHolder vh, GuidedAction action) {
                super.onBindViewHolder(vh, action);
                // Views are recycled between rows, so the null branch is
                // mandatory: without it, a row that once held the delay
                // listener would keep stepping the delay when reused.
                vh.itemView.setOnKeyListener(
                        action.getId() == ACTION_REAPPEAR_DELAY ? delayKeyListener : null);
            }
        };
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        Context context = requireContext();
        long id = action.getId();

        if (id == ACTION_ENABLE_OVERLAY) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + context.getPackageName())));
        } else if (id == ACTION_OVERLAY_REAPPEAR) {
            if (!Settings.canDrawOverlays(context)) {
                // Belt-and-suspenders: the row's .enabled(false) should
                // already stop this click from reaching here.
                return;
            }
            boolean enabled = !Preferences.isOverlayReappearEnabled(context);
            Preferences.setOverlayReappearEnabled(context, enabled);
            action.setChecked(enabled);
            action.setDescription(getString(enabled
                    ? R.string.settings_overlay_reappear_status_enabled
                    : R.string.settings_overlay_reappear_status_disabled));
            notifyActionChanged(getActions().indexOf(action));

            // The delay and long-press rows both depend on Reappear.
            GuidedAction delayAction = findActionById(ACTION_REAPPEAR_DELAY);
            if (delayAction != null) {
                delayAction.setEnabled(enabled);
                delayAction.setDescription(delayDescription(context, true, enabled));
                notifyActionChanged(getActions().indexOf(delayAction));
            }
            GuidedAction longPressAction = findActionById(ACTION_OVERLAY_LONG_PRESS);
            if (longPressAction != null) {
                longPressAction.setEnabled(enabled);
                longPressAction.setDescription(longPressDescription(context, true, enabled));
                notifyActionChanged(getActions().indexOf(longPressAction));
            }
        } else if (id == ACTION_REAPPEAR_DELAY) {
            if (action.isEnabled()) {
                stepDelay(1, true);
            }
        } else if (id == ACTION_OVERLAY_LONG_PRESS) {
            if (!Settings.canDrawOverlays(context) || !Preferences.isOverlayReappearEnabled(context)) {
                // Belt-and-suspenders, as above.
                return;
            }
            boolean enabled = !Preferences.isOverlayLongPressOnlyEnabled(context);
            Preferences.setOverlayLongPressOnlyEnabled(context, enabled);
            action.setChecked(enabled);
            action.setDescription(longPressDescription(context, true, true));
            notifyActionChanged(getActions().indexOf(action));
        }
    }
}
