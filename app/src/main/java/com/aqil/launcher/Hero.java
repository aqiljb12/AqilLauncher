package com.aqil.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Banner besar berputar (karusel) dengan peralihan 3D. Kiri/kanan tukar slaid, OK = tindakan. */
final class Hero extends Card {
    static final class Slide {
        String badge, title, sub, button, logoUrl;
        int badgeColor = Ui.RED, badgeIcon = R.drawable.ic_live;
        Drawable bg;
        Bitmap photo;
        Runnable action;
    }

    private final List<Slide> slides = new ArrayList<>();
    private final FrameLayout stage;
    private final ImageView bg, logo;
    private final TextView badgeText, title, sub, btnText;
    private final ImageView badgeIcon;
    private final LinearLayout badge, dots, btn;
    private int idx;
    private android.animation.ObjectAnimator kenBurns;
    private final Runnable auto = new Runnable() {
        @Override
        public void run() {
            if (slides.size() > 1) go(idx + 1, 1);
            postDelayed(this, 8000);
        }
    };

    Hero(Context c) {
        super(c, 34);
        scaleTo = 1.025f;
        stage = new FrameLayout(c);
        stage.setCameraDistance(getResources().getDisplayMetrics().density * 8000);
        addView(stage, new LayoutParams(-1, -1));

        bg = new ImageView(c);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        stage.addView(bg, new LayoutParams(-1, -1));
        // Ken Burns: latar banner zum & gerak perlahan tanpa henti (animasi GPU)
        if (Store.fx(c)) {
            android.animation.ObjectAnimator kb = android.animation.ObjectAnimator.ofPropertyValuesHolder(bg,
                    android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.14f),
                    android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.14f),
                    android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, -S.px(30)));
            kb.setDuration(14000);
            kb.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            kb.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            kb.start();
            kenBurns = kb;
        }
        logo = new ImageView(c);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        stage.addView(logo, Ui.at(610, 60, 330, 230));
        View shade = new View(c);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xE6000000, 0x80000000, 0x00000000}));
        stage.addView(shade, new LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        stage.addView(col, Ui.at(40, 36, 760, -2));

        badge = new LinearLayout(c);
        badge.setGravity(Gravity.CENTER_VERTICAL);
        badge.setPadding(S.px(16), S.px(8), S.px(20), S.px(8));
        badgeIcon = Ui.icon(c, R.drawable.ic_live, Ui.WHITE);
        badge.addView(badgeIcon, new LinearLayout.LayoutParams(S.px(30), S.px(30)));
        badgeText = Ui.text(c, "", 24, Ui.WHITE, Ui.MEDIUM);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.leftMargin = S.px(10);
        badge.addView(badgeText, blp);
        col.addView(badge, new LinearLayout.LayoutParams(-2, -2));

        title = Ui.text(c, "", 72, Ui.WHITE, Ui.BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = S.px(36);
        col.addView(title, tlp);
        sub = Ui.text(c, "", 30, 0xE6FFFFFF, Ui.MEDIUM);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = S.px(16);
        col.addView(sub, slp);

        btn = new LinearLayout(c);
        btn.setGravity(Gravity.CENTER_VERTICAL);
        btn.setBackground(Ui.solid(0xF2FFFFFF, S.px(36)));
        btn.setPadding(S.px(30), S.px(16), S.px(36), S.px(16));
        btn.addView(Ui.icon(c, R.drawable.ic_play, 0xFF111111), new LinearLayout.LayoutParams(S.px(34), S.px(34)));
        btnText = Ui.text(c, "", 30, 0xFF111111, Ui.MEDIUM);
        LinearLayout.LayoutParams btp = new LinearLayout.LayoutParams(-2, -2);
        btp.leftMargin = S.px(14);
        btn.addView(btnText, btp);
        LinearLayout.LayoutParams bnp = new LinearLayout.LayoutParams(-2, -2);
        bnp.topMargin = S.px(28);
        col.addView(btn, bnp);

        dots = new LinearLayout(c);
        dots.setGravity(Gravity.CENTER);
        addView(dots, Ui.at(0, 322, -1, 12));

        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (idx < slides.size() && slides.get(idx).action != null) slides.get(idx).action.run();
            }
        });
    }

    void setSlides(List<Slide> list) {
        slides.clear();
        slides.addAll(list);
        if (idx >= slides.size()) idx = 0;
        bind();
    }

    private void bind() {
        if (slides.isEmpty()) return;
        Slide s = slides.get(idx);
        if (s.photo != null) bg.setImageBitmap(s.photo);
        else bg.setImageDrawable(s.bg);
        if (s.logoUrl != null && !s.logoUrl.isEmpty()) {
            logo.setVisibility(VISIBLE);
            Img.load(logo, s.logoUrl, S.px(340));
        } else {
            logo.setVisibility(GONE);
        }
        badge.setBackground(Ui.solid(s.badgeColor, S.px(24)));
        badgeIcon.setImageDrawable(getResources().getDrawable(s.badgeIcon).mutate());
        badgeIcon.getDrawable().setTint(Ui.WHITE);
        badgeText.setText(s.badge);
        title.setText(s.title);
        sub.setText(s.sub);
        btnText.setText(s.button);
        dots.removeAllViews();
        for (int i = 0; i < slides.size(); i++) {
            View d = new View(getContext());
            d.setBackground(Ui.solid(i == idx ? 0xFFFFFFFF : 0x66FFFFFF, S.px(6)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(S.px(i == idx ? 34 : 12), S.px(12));
            lp.leftMargin = lp.rightMargin = S.px(5);
            dots.addView(d, lp);
        }
    }

    /** Peralihan 3D: slaid lama berpusing keluar, slaid baru berpusing masuk. */
    private void go(int to, final int dir) {
        if (slides.size() < 2) return;
        final int n = slides.size();
        final int target = ((to % n) + n) % n;
        stage.animate().cancel();
        stage.animate().rotationY(-22f * dir).translationX(-S.px(60) * dir).alpha(0f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator()).withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        idx = target;
                        bind();
                        stage.setRotationY(22f * dir);
                        stage.setTranslationX(S.px(60) * dir);
                        stage.animate().rotationY(0f).translationX(0f).alpha(1f).setDuration(380)
                                .setInterpolator(new DecelerateInterpolator()).withEndAction(null).start();
                    }
                }).start();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && idx < slides.size() - 1) {
            restartAuto();
            go(idx + 1, 1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && idx > 0) {
            restartAuto();
            go(idx - 1, -1);
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    private void restartAuto() {
        removeCallbacks(auto);
        postDelayed(auto, 10000);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        restartAuto();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (kenBurns != null) kenBurns.cancel();
        removeCallbacks(auto);
        super.onDetachedFromWindow();
    }
}
