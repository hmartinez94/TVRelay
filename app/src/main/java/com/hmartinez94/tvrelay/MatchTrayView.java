package com.hmartinez94.tvrelay;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.function.Consumer;

/**
 * The "which one did you mean?" tray shown when a title search returns
 * several exact matches (see MetadataResolver.isAmbiguous). Built
 * programmatically, like every other screen in this app - there is no
 * layout XML anywhere in the project, see MainActivity for why.
 *
 * Host-agnostic on purpose - MatchTrayOverlay is the only host now (see
 * that class for why a translucent-Activity host was tried and dropped),
 * but nothing here assumes that.
 *
 * Sized off screen resolution (uiScale below), not dp/density: a first
 * on-device pass sized everything in dp and came out oversized on the ONN,
 * which is consistent with TV boxes not always reporting a density that
 * matches their actual physical/logical pixel ratio the way phones do.
 * Scaling raw "reference pixels at a 1920x1080 screen" instead - the same
 * technique the reference app this was inspired by uses - renders
 * consistently across TV resolutions regardless of what a given box's
 * density happens to claim.
 *
 * D-pad LEFT/RIGHT between cards, and UP from a card to Cancel, is
 * Android's own default focus search - nothing here handles those keys
 * specially. That's different from WatchNowOverlay's blanket
 * dismiss-on-any-arrow-key, which exists there only because its Button is
 * the single focusable view in its window; this tray always has more than
 * one focusable view (several cards plus Cancel), so that workaround
 * doesn't apply and would only get in the way of navigating the tray.
 *
 * BACK, however, IS handled here, per focusable view rather than once on
 * the root: a confirmed on-device finding is that a key listener set only
 * on the tray's root container did not reliably see BACK once a deeper
 * child (a card) held focus - unlike WatchNowOverlay, whose single Button
 * is simultaneously the root view AND the focused view, so the same
 * approach worked fine there. Attaching the same listener to every
 * focusable leaf (each card, Cancel) sidesteps the question of exactly how
 * key events bubble back up through a ViewGroup chain, since the listener
 * is then always on whichever view actually holds focus.
 */
final class MatchTrayView {

    private MatchTrayView() {
    }

    static View build(Context context, String queryTitle, List<TitleCandidate> candidates,
                       Consumer<TitleCandidate> onPick, Runnable onCancel) {
        float scale = uiScale(context);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(px(scale, 24), px(scale, 16), px(scale, 24), px(scale, 16));
        content.setBackground(panelBackground(scale));

        LinearLayout heading = new LinearLayout(context);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(context);
        title.setText(context.getString(R.string.chooser_heading, queryTitle));
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(scale, 28));
        title.setMaxLines(1);
        heading.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView cancel = new TextView(context);
        cancel.setText(context.getString(R.string.chooser_cancel));
        cancel.setTextColor(focusAwareText(Color.rgb(20, 20, 24), Color.rgb(210, 210, 216)));
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(scale, 19));
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(px(scale, 18), px(scale, 9), px(scale, 18), px(scale, 9));
        cancel.setFocusable(true);
        cancel.setClickable(true);
        cancel.setBackground(cardBackground(scale));
        cancel.setOnClickListener(v -> onCancel.run());
        cancel.setOnKeyListener(backDismiss(onCancel));
        heading.addView(cancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content.addView(heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        View firstCard = null;
        for (TitleCandidate candidate : candidates) {
            View card = buildCard(context, scale, candidate, onPick, onCancel);
            if (firstCard == null) {
                firstCard = card;
            }
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.rightMargin = px(scale, 12);
            row.addView(card, cardParams);
        }

        HorizontalScrollView scroller = new HorizontalScrollView(context);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipToPadding(false);
        LinearLayout.LayoutParams scrollerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollerParams.topMargin = px(scale, 14);
        scroller.addView(row, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(scroller, scrollerParams);

        // requestFocus() here would very likely be a no-op or, worse, get
        // silently overridden: the view isn't attached to a window yet
        // (the host adds it to WindowManager right after this method
        // returns), and once attached, a focus search from the still-
        // unfocused root would land on Cancel - it comes before the row of
        // cards in child order and is the first focusable view found. A
        // short delay, targeting the card directly, avoids both problems.
        if (firstCard != null) {
            View target = firstCard;
            new Handler(Looper.getMainLooper()).postDelayed(target::requestFocus, 150L);
        }
        return content;
    }

    private static View buildCard(Context context, float scale, TitleCandidate candidate,
                                   Consumer<TitleCandidate> onPick, Runnable onCancel) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(px(scale, 20), px(scale, 12), px(scale, 20), px(scale, 12));
        card.setFocusable(true);
        card.setClickable(true);
        card.setBackground(cardBackground(scale));
        card.setOnClickListener(v -> onPick.accept(candidate));
        card.setOnKeyListener(backDismiss(onCancel));

        TextView year = new TextView(context);
        year.setText(candidate.year != Integer.MIN_VALUE
                ? String.valueOf(candidate.year)
                : context.getString(R.string.chooser_unknown_year));
        year.setTextColor(focusAwareText(Color.rgb(20, 20, 24), Color.WHITE));
        year.setDuplicateParentStateEnabled(true);
        year.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(scale, 30));
        year.setGravity(Gravity.CENTER);
        year.setMaxLines(1);
        card.addView(year);

        TextView type = new TextView(context);
        type.setText(context.getString(candidate.type == MediaType.MOVIE
                ? R.string.chooser_type_movie
                : R.string.chooser_type_series));
        type.setTextColor(focusAwareText(Color.rgb(60, 60, 66), Color.rgb(190, 190, 197)));
        type.setDuplicateParentStateEnabled(true);
        type.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(scale, 17));
        type.setGravity(Gravity.CENTER);
        type.setMaxLines(1);
        card.addView(type);

        // Surfaces a real confirmed case: TMDB's own localized title for an
        // obscure, unrelated film was itself literally the searched title,
        // so it's a legitimate exact match by the data - but showing its
        // real (original-language) title here at least lets the user spot
        // it isn't what they expected instead of it looking identical to a
        // genuine match. See TmdbClient.
        if (candidate.akaTitle != null) {
            TextView aka = new TextView(context);
            aka.setText(context.getString(R.string.chooser_aka, candidate.akaTitle));
            aka.setTextColor(focusAwareText(Color.rgb(70, 70, 76), Color.rgb(150, 150, 158)));
            aka.setDuplicateParentStateEnabled(true);
            aka.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(scale, 13));
            aka.setGravity(Gravity.CENTER);
            aka.setMaxLines(1);
            aka.setEllipsize(android.text.TextUtils.TruncateAt.END);
            aka.setMaxWidth(px(scale, 220));
            card.addView(aka);
        }

        return card;
    }

    private static View.OnKeyListener backDismiss(Runnable onCancel) {
        return (v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                onCancel.run();
                return true;
            }
            return false;
        };
    }

    /**
     * A card's year/type TextViews aren't themselves focusable (only the
     * card is), so they rely on setDuplicateParentStateEnabled(true) to
     * pick up state_focused from their parent - Cancel IS itself the
     * focusable view, so it needs no such flag. Without this, focused-card
     * text stayed the same light color against cardBackground()'s
     * near-white focused fill and became unreadable - confirmed on-device.
     */
    private static ColorStateList focusAwareText(int focusedColor, int normalColor) {
        return new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}},
                new int[]{focusedColor, normalColor});
    }

    private static StateListDrawable cardBackground(float scale) {
        StateListDrawable drawable = new StateListDrawable();
        drawable.addState(new int[]{android.R.attr.state_focused},
                shape(scale, Color.rgb(242, 242, 238), Color.WHITE));
        drawable.addState(new int[]{},
                shape(scale, Color.rgb(50, 52, 59), Color.rgb(91, 94, 105)));
        return drawable;
    }

    private static GradientDrawable shape(float scale, int fill, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(px(scale, 10));
        shape.setStroke(Math.max(1, px(scale, 1)), stroke);
        return shape;
    }

    private static GradientDrawable panelBackground(float scale) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.rgb(28, 30, 35));
        shape.setCornerRadius(px(scale, 14));
        shape.setStroke(Math.max(1, px(scale, 1)), Color.rgb(71, 73, 82));
        return shape;
    }

    /** How many actual pixels a "reference pixel at 1920x1080" maps to on this screen. */
    private static float uiScale(Context context) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        float scale = Math.min(metrics.widthPixels / 1920f, metrics.heightPixels / 1080f);
        return Math.max(scale, 0.65f);
    }

    private static int px(float scale, int referencePixels) {
        return Math.round(referencePixels * scale);
    }
}
