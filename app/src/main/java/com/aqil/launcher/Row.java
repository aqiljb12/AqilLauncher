package com.aqil.launcher;

import android.content.Context;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Baris kaca boleh fokus: tajuk, sub-tajuk dan nilai di kanan (menu, tetapan, senarai). */
final class Row extends Card {
    private final TextView title, sub, right;

    Row(Context c, String t, String s, String r) {
        super(c, 22);
        scaleTo = 1.03f;
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(S.px(30), 0, S.px(200), 0);
        title = Ui.text(c, t, 30, Ui.WHITE, Ui.MEDIUM);
        sub = Ui.text(c, "", 22, Ui.DIM, Ui.MEDIUM);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = S.px(8);
        box.addView(title);
        box.addView(sub, lp);
        addView(box, new FrameLayout.LayoutParams(-1, -1));
        right = Ui.text(c, "", 26, 0xFF64B5FF, Ui.MEDIUM);
        right.setGravity(Gravity.END);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(S.px(220), -2, Gravity.END | Gravity.CENTER_VERTICAL);
        rp.rightMargin = S.px(30);
        addView(right, rp);
        set(t, s, r);
    }

    Row set(String t, String s, String r) {
        title.setText(t);
        sub.setText(s == null ? "" : s);
        sub.setVisibility(s == null ? GONE : VISIBLE);
        right.setText(r == null ? "" : r);
        return this;
    }
}
