package com.aqil.launcher;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

/**
 * LinearLayout yang melukis anak yang difokus PALING AKHIR, jadi kad yang membesar berada di atas jirannya
 * tanpa elevation/Z. Dipakai dalam kawasan skrol: paparan ber-Z dilukis dalam laluan susunan-Z GPU yang
 * pada sesetengah TV tidak dipotong oleh ScrollView, lalu terkeluar ke atas tajuk/jam.
 */
final class FrontLayout extends LinearLayout {
    FrontLayout(Context c, int orientation) {
        super(c);
        setOrientation(orientation);
        setChildrenDrawingOrderEnabled(true);
        Ui.noClip(this);
    }

    private int focusedIndex(int count) {
        for (int k = 0; k < count; k++) {
            View v = getChildAt(k);
            if (v != null && (v.hasFocus() || v.isFocused())) return k;
        }
        return -1;
    }

    @Override
    protected int getChildDrawingOrder(int count, int i) {
        int f = focusedIndex(count);
        if (f < 0 || f >= count) return i;
        if (i == count - 1) return f;
        return i >= f ? i + 1 : i;
    }

    @Override
    public void requestChildFocus(View child, View focused) {
        super.requestChildFocus(child, focused);
        invalidate();
    }

    @Override
    public void clearChildFocus(View child) {
        super.clearChildFocus(child);
        invalidate();
    }
}
