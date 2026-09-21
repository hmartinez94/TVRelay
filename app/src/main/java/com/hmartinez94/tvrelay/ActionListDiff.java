package com.hmartinez94.tvrelay;

import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.DiffCallback;
import androidx.leanback.widget.GuidedAction;

/**
 * Diff rule for GuidedStepSupportFragment.setActions() that re-binds any row
 * whose GuidedAction object was replaced.
 *
 * Leanback's default GuidedActionDiffCallback treats two actions with the
 * same id and the same title/description/flags as "the same content", and
 * then leaves the on-screen row bound to the OLD GuidedAction object. Every
 * screen here rebuilds its list with brand-new action objects on resume, so
 * clicks were delivered to objects that were no longer in the fragment's
 * list. For a dropdown row that is fatal and silent: expandAction() looks
 * the clicked object up in the list (by identity), gets -1 and returns, so
 * the "Player app" dropdown simply stopped opening after the screen had been
 * rebuilt once (confirmed on the ONN, 2026-09-21). Checkbox rows were hit
 * too: getActions().indexOf(action) came back -1, so their status text never
 * refreshed. Comparing by identity instead makes every replaced row re-bind
 * to its current object, while still diffing by id so focus and insert/remove
 * animations behave as before (unlike setActionsDiffCallback(null), which
 * refreshes the whole list).
 */
final class ActionListDiff extends DiffCallback<GuidedAction> {

    private static final ActionListDiff INSTANCE = new ActionListDiff();

    private ActionListDiff() {
    }

    /** Call once the fragment's view exists (its adapter is created in onCreateView). */
    static void install(GuidedStepSupportFragment fragment) {
        fragment.setActionsDiffCallback(INSTANCE);
    }

    @Override
    public boolean areItemsTheSame(GuidedAction oldItem, GuidedAction newItem) {
        return oldItem.getId() == newItem.getId();
    }

    @Override
    public boolean areContentsTheSame(GuidedAction oldItem, GuidedAction newItem) {
        return oldItem == newItem;
    }
}
