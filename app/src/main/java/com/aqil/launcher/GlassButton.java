package com.aqil.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;

/** Baris/butang kaca untuk menu, tetapan dan senarai. */
final class GlassButton extends View {
    private final float d;
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG), sp = new TextPaint(Paint.ANTI_ALIAS_FLAG), rp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private String title, sub, right;
    private float focus, target;
    private ValueAnimator anim;
    private boolean marked;

    GlassButton(Context c, String title, String sub, int widthDp, int heightDp) {
        super(c);
        d = U.dp(c, 1);
        this.title = title;
        this.sub = sub;
        setFocusable(true);
        setClickable(true);
        tp.setColor(0xFFFFFFFF);
        tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tp.setTextSize(U.sp(c, 17));
        sp.setColor(0xB3FFFFFF);
        sp.setTextSize(U.sp(c, 13));
        rp.setColor(0xFF64D2FF);
        rp.setTextSize(U.sp(c, 15));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(widthDp <= 0 ? LinearLayout.LayoutParams.MATCH_PARENT : (int) (widthDp * d),
                (int) (heightDp * d));
        lp.setMargins(0, (int) (4 * d), 0, (int) (4 * d));
        setLayoutParams(lp);
    }

    void set(String title, String sub, String right) {
        this.title = title;
        this.sub = sub;
        this.right = right;
        invalidate();
    }

    /** Tanda baris yang sedang dimainkan / dipilih. */
    void setMarked(boolean m) {
        marked = m;
        invalidate();
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction, Rect prev) {
        super.onFocusChanged(gained, direction, prev);
        go(gained ? 1f : 0f);
    }

    @Override
    public void setSelected(boolean s) {
        super.setSelected(s);
        go(s ? 1f : 0f);
    }

    private void go(float t) {
        target = t;
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(focus, t);
        anim.setDuration(180);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                focus = (Float) a.getAnimatedValue();
                float s = 1f + 0.035f * focus;
                setScaleX(s);
                setScaleY(s);
                invalidate();
            }
        });
        anim.start();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float m = 4 * d;
        Glass.plate(c, d, m, m, w - m, h - m, Math.min(20 * d, (h - 2 * m) / 2f), focus, marked ? 0xFF0A84FF : 0, marked ? 0xFF5E5CE6 : 0, -1f);
        float x = 22 * d;
        float rw = right == null ? 0 : rp.measureText(right) + 30 * d;
        if (right != null) c.drawText(right, w - rw + 8 * d, h / 2f + 5 * d, rp);
        String t = TextUtils.ellipsize(title == null ? "" : title, tp, w - x - rw - 20 * d, TextUtils.TruncateAt.END).toString();
        if (sub == null) {
            c.drawText(t, x, h / 2f + 6 * d, tp);
        } else {
            c.drawText(t, x, h / 2f - 3 * d, tp);
            c.drawText(TextUtils.ellipsize(sub, sp, w - x - rw - 20 * d, TextUtils.TruncateAt.END).toString(), x, h / 2f + 17 * d, sp);
        }
    }
}
