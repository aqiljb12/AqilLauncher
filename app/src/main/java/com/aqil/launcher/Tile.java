package com.aqil.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Kad kaca 3D. Bila difokus: membesar, timbul (translationZ), bergoyang lembut dalam 3D, dan
 * sinaran berkilau melintas. Jiran dalam Rail condong ke arah kad fokus (gaya cover-flow).
 */
final class Tile extends View {
    final boolean hero;
    private final float d;
    private final Drawable icon;
    private final String title, sub;
    private final int tintA, tintB;
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG), sp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Rect ib = new Rect();
    Rail rail;
    String tag = "";

    private float focus, tilt, tiltTarget, press, phase;
    private ValueAnimator ticker;

    Tile(Context c, Drawable icon, String title, String sub, boolean hero, int tintA, int tintB) {
        super(c);
        this.d = U.dp(c, 1);
        this.icon = icon;
        this.title = title;
        this.sub = sub;
        this.hero = hero;
        this.tintA = tintA;
        this.tintB = tintB;
        setFocusable(true);
        setClickable(true);
        setClipToOutline(false);
        tp.setColor(0xFFFFFFFF);
        tp.setTypeface(Typeface.create("sans-serif-medium", hero ? Typeface.BOLD : Typeface.NORMAL));
        tp.setTextSize(U.sp(c, hero ? 22 : 15));
        sp.setColor(0xCCFFFFFF);
        sp.setTextSize(U.sp(c, 13));
        setCameraDistance(U.dp(c, 1400) * 1f);
        setLayoutParams(new android.widget.LinearLayout.LayoutParams((int) (hero ? 268 * d : 150 * d), (int) (hero ? 156 * d : 188 * d)));
    }

    void setTiltTarget(float deg) {
        tiltTarget = deg;
        wake();
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction, Rect prev) {
        super.onFocusChanged(gained, direction, prev);
        if (gained) {
            if (rail != null) rail.onTileFocus(this);
            Context cx = getContext();
            if (cx instanceof BaseActivity) {
                int[] loc = new int[2];
                getLocationOnScreen(loc);
                int sw = getResources().getDisplayMetrics().widthPixels, sh = getResources().getDisplayMetrics().heightPixels;
                ((BaseActivity) cx).parallax((loc[0] + getWidth() / 2f) / sw * 2 - 1, (loc[1] + getHeight() / 2f) / sh * 2 - 1);
            }
        } else if (rail != null) {
            rail.post(new Runnable() {
                @Override
                public void run() {
                    rail.maybeRelax();
                }
            });
        }
        wake();
    }

    @Override
    public void setPressed(boolean p) {
        super.setPressed(p);
        wake();
    }

    private void wake() {
        if (ticker != null && ticker.isRunning()) return;
        ticker = ValueAnimator.ofFloat(0f, 1f);
        ticker.setDuration(2600);
        ticker.setRepeatCount(ValueAnimator.INFINITE);
        ticker.setInterpolator(new LinearInterpolator());
        ticker.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                step(a);
            }
        });
        ticker.start();
    }

    private void step(ValueAnimator a) {
        boolean f = isFocused();
        float tf = f ? 1f : 0f;
        focus += (tf - focus) * 0.2f;
        tilt += (tiltTarget - tilt) * 0.16f;
        press += ((isPressed() ? 1f : 0f) - press) * 0.35f;
        phase = (Float) a.getAnimatedValue();
        double ang = phase * Math.PI * 2;
        float wob = focus;
        float s = 1f + (hero ? 0.09f : 0.14f) * focus + 0.012f * wob * (float) Math.sin(ang) - 0.05f * press;
        setScaleX(s);
        setScaleY(s);
        setRotationY(tilt + 3.2f * wob * (float) Math.sin(ang));
        setRotationX(2.4f * wob * (float) Math.cos(ang));
        setTranslationY(-5 * d * wob * (float) Math.sin(ang + 1f) - 8 * d * focus);
        setTranslationZ(40 * d * focus);
        invalidate();
        boolean settled = !f && focus < 0.01f && Math.abs(tilt - tiltTarget) < 0.15f && press < 0.01f;
        if (settled) {
            focus = 0;
            tilt = tiltTarget;
            setScaleX(1f);
            setScaleY(1f);
            setRotationY(tilt);
            setRotationX(0);
            setTranslationY(0);
            setTranslationZ(0);
            a.cancel();
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float m = 8 * d;
        float shine = isFocused() ? -0.3f + 1.6f * ((phase * 1.6f) % 1f) : -1f;
        if (hero) {
            Glass.plate(c, d, m, m, w - m, h - m, 26 * d, focus, tintA, tintB, shine);
            int is = (int) (46 * d);
            if (icon != null) {
                icon.setBounds((int) (24 * d), (int) (24 * d), (int) (24 * d) + is, (int) (24 * d) + is);
                icon.setAlpha(255);
                icon.draw(c);
            }
            c.drawText(TextUtils.ellipsize(title, tp, w - 48 * d, TextUtils.TruncateAt.END).toString(), 24 * d, h - 52 * d, tp);
            if (sub != null) c.drawText(TextUtils.ellipsize(sub, sp, w - 48 * d, TextUtils.TruncateAt.END).toString(), 24 * d, h - 28 * d, sp);
        } else {
            float s = w - 2 * m;
            Glass.plate(c, d, m, m, m + s, m + s, 32 * d, focus, 0, 0, shine);
            if (icon != null) {
                float pad = s * 0.17f;
                icon.setBounds((int) (m + pad), (int) (m + pad), (int) (m + s - pad), (int) (m + s - pad));
                icon.draw(c);
            }
            tp.setAlpha((int) (200 + 55 * focus));
            String t = TextUtils.ellipsize(title, tp, w - 10 * d, TextUtils.TruncateAt.END).toString();
            c.drawText(t, (w - tp.measureText(t)) / 2f, m + s + 28 * d, tp);
        }
    }
}
