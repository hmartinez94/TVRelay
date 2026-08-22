package com.hmartinez94.tvrelay;

import androidx.leanback.widget.GuidedAction;

import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.LongPredicate;

/**
 * Leanback's checkSetId auto-exclusivity for GuidedAction radio groups is
 * unreliable on real hardware - confirmed on-device (ONN 4K Pro): a player
 * choice became unselectable while two others stayed checked. Every radio
 * group in this app (player choice, metadata provider choice) enforces
 * exclusivity itself as a result; this is the shared "check the clicked
 * action, uncheck the rest of its group" loop the call sites used to
 * duplicate. See CLAUDE.md for the original bug report.
 */
final class RadioActionHelper {

    private RadioActionHelper() {
    }

    /**
     * @param actions       the fragment's current action list (getActions())
     * @param selectedId    the id of the action the user just clicked
     * @param isInGroup     true for every action id that belongs to this radio group
     * @param notifyChanged the fragment's own notifyActionChanged(int) - pass as this::notifyActionChanged
     */
    static void enforceExclusivity(List<GuidedAction> actions, long selectedId,
                                    LongPredicate isInGroup, IntConsumer notifyChanged) {
        for (int i = 0; i < actions.size(); i++) {
            GuidedAction candidate = actions.get(i);
            if (!isInGroup.test(candidate.getId())) {
                continue;
            }
            boolean shouldBeChecked = candidate.getId() == selectedId;
            if (candidate.isChecked() != shouldBeChecked) {
                candidate.setChecked(shouldBeChecked);
                notifyChanged.accept(i);
            }
        }
    }
}
