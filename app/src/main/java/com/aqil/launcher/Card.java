package com.aqil.launcher;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

/**
 * Kad kaca boleh fokus. Kesan 3D bila difokus: condong masuk dari arah D-pad, membesar dengan
 * lantunan, timbul dengan bayang biru, cincin putih dan sinaran melintas sekali.
 * Semua animasi guna sifat RenderNode (skala/putaran/Z) – tiada lukisan semula, jadi lancar.
 */
class Card extends FrameLayout {
    interface OnFocus {
        void onFocus(Card c, boolean gained);
    }

    private final View ring, shine;
    float scaleTo = 1.07f;
    OnFocus onFocus;
    /** Warna ambien latar bila kad ini difokus (0 = tiada). */
    int ambient;
    /** Kad rata (tanpa elevation/Z) – untuk kawasan skrol; dipasangkan dengan FrontLayout. */
    private boolean flat;

    Card flat() {
        flat = true;
        setElevation(0);
        setTranslationZ(0);
        return this;
    }

    private float liftZ() {
        return flat ? 0 : S.px(22);
    }

    Card(Context c, float radius) {
        this(c, radius, Ui.glass(S.px(radius)));
    }

    Card(Context c, float radius, android.graphics.drawable.Drawable bg) {
        super(c);
        setFocusable(true);
        setClickable(true);
        setBackground(bg);
        setClipToOutline(true);
        setElevation(S.px(3));
        setCameraDistance(getResources().getDisplayMetrics().density * 6000);
        if (Build.VERSION.SDK_INT >= 28) {
            setOutlineSpotShadowColor(0xFF2E8BFF);
            setOutlineAmbientShadowColor(0xFF2E8BFF);
        }
        ring = new View(c);
        ring.setBackground(Ui.ring(S.px(radius)));
        ring.setAlpha(0f);
        shine = new View(c);
        shine.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x00FFFFFF, 0x38FFFFFF, 0x00FFFFFF}));
        shine.setAlpha(0f);
        addView(ring, new LayoutParams(-1, -1));
        addView(shine, new LayoutParams(-1, -1));
    }

    /** Kandungan sentiasa di bawah cincin & sinaran. */
    @Override
    public void addView(View child, int index, ViewGroup.LayoutParams params) {
        if (ring == null || shine == null || child == ring || child == shine) {
            super.addView(child, index, params);
        } else {
            super.addView(child, indexOfChild(ring), params);
        }
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction, Rect prev) {
        super.onFocusChanged(gained, direction, prev);
        animateFocus(gained);
        if (onFocus != null) onFocus.onFocus(this, gained);
        if (gained && getContext() instanceof BaseActivity) {
            if (ambient != 0) ((BaseActivity) getContext()).ambient(ambient);
            int[] l = new int[2];
            getLocationOnScreen(l);
            ((BaseActivity) getContext()).parallax((l[0] + getWidth() / 2f) / S.w * 2 - 1, (l[1] + getHeight() / 2f) / S.h * 2 - 1);
        }
    }

    /** Denyut tekan: kad "ditekan masuk" dalam 3D kemudian melantun – maklum balas jelas bila OK ditekan. */
    @Override
    public boolean performClick() {
        animate().cancel();
        animate().scaleX(scaleTo * 0.92f).scaleY(scaleTo * 0.92f).translationZ(flat ? 0 : S.px(4)).rotationX(8f).setDuration(90)
                .setInterpolator(new DecelerateInterpolator()).withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        float s = isFocused() ? scaleTo : 1f;
                        animate().scaleX(s).scaleY(s).translationZ(isFocused() ? liftZ() : 0).rotationX(0f).setDuration(260)
                                .setInterpolator(new OvershootInterpolator(2f)).withEndAction(null).start();
                    }
                }).start();
        return super.performClick();
    }

    void animateFocus(boolean gained) {
        animate().cancel();
        if (gained) {
            if (Store.fx(getContext())) {
                int dir = BaseActivity.lastDir;
                setRotationY(dir == View.FOCUS_RIGHT ? -14f : dir == View.FOCUS_LEFT ? 14f : 0f);
                setRotationX(dir == View.FOCUS_DOWN ? 12f : dir == View.FOCUS_UP ? -12f : 0f);
            }
            animate().scaleX(scaleTo).scaleY(scaleTo).translationZ(liftZ()).rotationX(0).rotationY(0)
                    .setDuration(340).setInterpolator(new OvershootInterpolator(1.3f)).start();
            ring.animate().alpha(1f).setDuration(150).start();
            if (Store.fx(getContext()) && getWidth() > 0) {
                shine.animate().cancel();
                shine.setTranslationX(-getWidth());
                shine.setAlpha(1f);
                shine.animate().translationX(getWidth()).setDuration(750).setStartDelay(90)
                        .setInterpolator(new DecelerateInterpolator()).withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                shine.setAlpha(0f);
                            }
                        }).start();
            }
        } else {
            animate().scaleX(1f).scaleY(1f).translationZ(0).rotationX(0).rotationY(0)
                    .setDuration(200).setInterpolator(new DecelerateInterpolator()).start();
            ring.animate().alpha(0f).setDuration(150).start();
        }
    }
}
