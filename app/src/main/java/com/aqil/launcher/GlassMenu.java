package com.aqil.launcher;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Menu pop-up kaca (tekan lama OK pada apl, dsb). */
final class GlassMenu {
    private final Dialog dlg;
    private final LinearLayout box;
    private final Context c;
    private final float d;

    GlassMenu(Context c, String title) {
        this.c = c;
        d = U.dp(c, 1);
        dlg = new Dialog(c, android.R.style.Theme_Translucent_NoTitleBar);
        FrameLayout root = new FrameLayout(c);
        root.setBackgroundColor(0x99000000);
        box = new LinearLayout(c) {
            @Override
            protected void onDraw(android.graphics.Canvas cv) {
                Glass.plate(cv, d, 0, 0, getWidth(), getHeight(), 30 * d, 0f, 0, 0, -1f);
            }
        };
        box.setWillNotDraw(false);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (24 * d), (int) (22 * d), (int) (24 * d), (int) (18 * d));
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(20);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        t.setPadding((int) (6 * d), 0, 0, (int) (12 * d));
        box.addView(t);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) (460 * d), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(box, lp);
        dlg.setContentView(root);
        Window w = dlg.getWindow();
        if (w != null) w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    GlassMenu note(String text, float sizeSp, int color) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(sizeSp);
        t.setPadding((int) (6 * d), (int) (2 * d), (int) (6 * d), (int) (8 * d));
        box.addView(t);
        return this;
    }

    GlassMenu add(String label, final Runnable action) {
        GlassButton b = new GlassButton(c, label, null, 0, 56);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
                if (action != null) action.run();
            }
        });
        box.addView(b);
        return this;
    }

    void show() {
        dlg.show();
        box.setScaleX(0.85f);
        box.setScaleY(0.85f);
        box.setAlpha(0f);
        box.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).start();
        for (int i = 0; i < box.getChildCount(); i++) {
            if (box.getChildAt(i) instanceof GlassButton) {
                box.getChildAt(i).requestFocus();
                break;
            }
        }
    }
}
