package com.aqil.launcher;

import android.content.Context;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

/** Baris mendatar kad. Memusat kad fokus dan condongkan jiran dalam 3D (cover-flow). */
final class Rail extends HorizontalScrollView {
    interface Host {
        void onRailFocus(Rail r, Tile t);
    }

    final LinearLayout row;

    Rail(Context c) {
        super(c);
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setClipChildren(false);
        setClipToPadding(false);
        setFillViewport(false);
        row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        addView(row, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    void add(Tile t) {
        t.rail = this;
        row.addView(t);
    }

    void onTileFocus(Tile focused) {
        int idx = row.indexOfChild(focused);
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            if (v instanceof Tile) ((Tile) v).setTiltTarget(U.clamp((idx - i) * 9f, -26f, 26f));
        }
        int target = focused.getLeft() + focused.getWidth() / 2 - getWidth() / 2;
        smoothScrollTo(Math.max(0, target), 0);
        Context c = getContext();
        if (c instanceof Host) ((Host) c).onRailFocus(this, focused);
    }

    void maybeRelax() {
        if (hasFocus()) return;
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            if (v instanceof Tile) ((Tile) v).setTiltTarget(0f);
        }
    }

    @Override
    public void requestChildFocus(View child, View focused) {
        super.requestChildFocus(child, focused);
    }

    /** Elak HorizontalScrollView menggulung sendiri (kita pusatkan sendiri). */
    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect rect) {
        return 0;
    }
}
