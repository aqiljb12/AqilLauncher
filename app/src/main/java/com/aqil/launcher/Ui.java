package com.aqil.launcher;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Pembantu UI: kaca, teks, ikon dan kedudukan dalam unit reka bentuk 1920x1080. */
final class Ui {
    static final int WHITE = 0xFFFFFFFF, DIM = 0xB3FFFFFF, FAINT = 0x80FFFFFF, BLUE = 0xFF2E8BFF, RED = 0xFFE5263B;
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);
    static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);

    private Ui() {}

    /** Kaca gelap lut sinar dengan bingkai halus (dilukis terus oleh GPU, murah). */
    static GradientDrawable glass(float radiusPx) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x66262B3A, 0x7A10131C});
        g.setCornerRadius(radiusPx);
        g.setStroke(Math.max(1, S.px(1.5f)), 0x38FFFFFF);
        return g;
    }

    static GradientDrawable solid(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static GradientDrawable grad(GradientDrawable.Orientation o, float radiusPx, int... colors) {
        GradientDrawable g = new GradientDrawable(o, colors);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static GradientDrawable ring(float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x14FFFFFF);
        g.setCornerRadius(radiusPx);
        g.setStroke(Math.max(2, S.px(3.5f)), 0xF2FFFFFF);
        return g;
    }

    static GradientDrawable selected(float radiusPx) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x552E8BFF, 0x401A4FA8});
        g.setCornerRadius(radiusPx);
        g.setStroke(Math.max(2, S.px(2.5f)), 0xFF5AA9FF);
        return g;
    }

    static TextView text(Context c, CharSequence s, float size, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, S.px(size));
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    static ImageView icon(Context c, int res, int tint) {
        ImageView v = new ImageView(c);
        Drawable d = c.getResources().getDrawable(res).mutate();
        if (tint != 0) d.setTint(tint);
        v.setImageDrawable(d);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return v;
    }

    static ImageView image(Context c, Drawable d) {
        ImageView v = new ImageView(c);
        v.setImageDrawable(d);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return v;
    }

    /** LayoutParams FrameLayout pada (x,y) dengan saiz w,h (unit reka bentuk; -1 match, -2 wrap). */
    static FrameLayout.LayoutParams at(float x, float y, float w, float h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dim(w), dim(h));
        lp.leftMargin = S.px(x);
        lp.topMargin = S.px(y);
        return lp;
    }

    static LinearLayout.LayoutParams lin(float w, float h, float marginEnd) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dim(w), dim(h));
        lp.rightMargin = S.px(marginEnd);
        return lp;
    }

    static int dim(float v) {
        return v == -1 ? -1 : v == -2 ? -2 : S.px(v);
    }

    /**
     * Klip kawasan skrol pada sempadannya sendiri. Perlu kerana halaman memakai clipChildren=false (supaya kad
     * yang membesar tak terpotong); tanpa ini kad ber-elevation yang diskrol keluar dilukis di atas tajuk/jam.
     * Klip outline ialah sifat RenderNode, jadi ia turut memotong paparan ber-Z (berbeza dengan clipRect biasa).
     */
    static void clipToBounds(View... vs) {
        for (View v : vs) {
            v.setOutlineProvider(android.view.ViewOutlineProvider.BOUNDS);
            v.setClipToOutline(true);
        }
    }

    static void noClip(View... vs) {
        for (View v : vs) {
            if (v instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) v).setClipChildren(false);
                ((android.view.ViewGroup) v).setClipToPadding(false);
            }
        }
    }

    static String ago(long t) {
        long m = (System.currentTimeMillis() - t) / 60000;
        if (m < 1) return "Baru sahaja";
        if (m < 60) return m + " min lalu";
        long h = m / 60;
        if (h < 24) return h + " jam lalu";
        return (h / 24) + " hari lalu";
    }
}
