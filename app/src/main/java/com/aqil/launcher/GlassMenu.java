package com.aqil.launcher;

import android.app.Dialog;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Menu pop-up kaca (tekan lama OK / butang Menu pada apl, dsb). */
final class GlassMenu {
    private final Dialog dlg;
    private final LinearLayout box;
    private final Context c;
    private View firstButton;

    GlassMenu(Context c, String title) {
        this.c = c;
        dlg = new Dialog(c, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        FrameLayout root = new FrameLayout(c);
        root.setBackgroundColor(0xAA000000);
        box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.glass(S.px(36)));
        box.setPadding(S.px(30), S.px(28), S.px(30), S.px(24));
        Ui.noClip(root, box);
        TextView t = Ui.text(c, title, 36, Ui.WHITE, Ui.BOLD);
        t.setPadding(S.px(6), 0, 0, S.px(16));
        box.addView(t);
        root.addView(box, new FrameLayout.LayoutParams(S.px(640), -2, Gravity.CENTER));
        dlg.setContentView(root);
        Window w = dlg.getWindow();
        if (w != null) w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    GlassMenu note(String text, float size, int color) {
        TextView t = Ui.text(c, text, size, color, Ui.MEDIUM);
        t.setSingleLine(false);
        t.setPadding(S.px(6), S.px(4), S.px(6), S.px(12));
        box.addView(t);
        return this;
    }

    /** Kotak teks (untuk log masuk akaun dsb). */
    android.widget.EditText field(String hint, String value, boolean password) {
        android.widget.EditText e = new android.widget.EditText(c);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setTextColor(Ui.WHITE);
        e.setHintTextColor(Ui.FAINT);
        e.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, S.px(26));
        e.setBackground(Ui.glass(S.px(18)));
        e.setPadding(S.px(22), S.px(14), S.px(22), S.px(14));
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | (password
                ? android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD : android.text.InputType.TYPE_TEXT_VARIATION_URI));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = S.px(10);
        box.addView(e, lp);
        if (firstButton == null) firstButton = e;
        return e;
    }

    GlassMenu add(String label, final Runnable action) {
        Row b = new Row(c, label, null, null);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
                if (action != null) action.run();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, S.px(78));
        lp.topMargin = S.px(10);
        box.addView(b, lp);
        if (firstButton == null) firstButton = b;
        return this;
    }

    void show() {
        dlg.show();
        box.setScaleX(0.86f);
        box.setScaleY(0.86f);
        box.setRotationX(14f);
        box.setAlpha(0f);
        box.animate().scaleX(1f).scaleY(1f).rotationX(0f).alpha(1f).setDuration(260).start();
        if (firstButton != null) firstButton.requestFocus();
    }
}
