package com.aqil.launcher;

import android.content.Context;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Butang navigasi (sidebar kiri & dock bawah): ikon + label, sorotan biru bila halaman aktif. */
final class NavItem extends Card {
    final String page;
    private final TextView label;
    private final ImageView icon;
    private final float radius;

    NavItem(Context c, String page, int iconRes, String text, float radius) {
        super(c, radius, Ui.solid(0x00000000, S.px(radius)));
        this.page = page;
        this.radius = radius;
        scaleTo = 1.1f;
        setElevation(0);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        icon = Ui.icon(c, iconRes, Ui.WHITE);
        box.addView(icon, new LinearLayout.LayoutParams(S.px(44), S.px(44)));
        label = Ui.text(c, text, 22, Ui.DIM, Ui.MEDIUM);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = S.px(8);
        box.addView(label, lp);
        addView(box, new LayoutParams(-1, -1));
    }

    void setActive(boolean on) {
        label.setTextColor(on ? Ui.WHITE : Ui.DIM);
        icon.setAlpha(on ? 1f : 0.75f);
    }
}
