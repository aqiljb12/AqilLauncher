package com.aqil.launcher;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Latar launcher:
 *  - "aurora" / "nebula": wallpaper LIVE terbina (gumpalan cahaya bergerak perlahan; GPU sahaja, sangat ringan)
 *  - "video": wallpaper LIVE dari video yang dihantar dari telefon (berulang, senyap)
 *  - "grad:night" / "grad:dusk": gradien statik
 *  - "photo:NAMA": gambar dari galeri telefon
 */
final class WallpaperView extends FrameLayout {
    private final FrameLayout layer;
    private final List<ObjectAnimator> anims = new ArrayList<>();
    private MediaPlayer mp;
    private TextureView tex;
    private String spec = "";
    private boolean paused;
    private int videoW, videoH;

    WallpaperView(Context c) {
        super(c);
        layer = new FrameLayout(c);
        // sedikit lebih besar dari skrin supaya parallax tak tunjuk tepi
        layer.setScaleX(1.06f);
        layer.setScaleY(1.06f);
        addView(layer, new LayoutParams(-1, -1));
        setBackgroundColor(0xFF05070F);
    }

    /** Paksa apply() seterusnya memuat semula walaupun spec sama. */
    void reset() {
        spec = "";
    }

    void apply(String s) {
        if (s == null) s = "aurora";
        if (s.equals(spec)) return;
        spec = s;
        clear();
        if (s.equals("aurora")) {
            live(0xFF0B1230, 0xFF1A0F2E, new int[]{0xFF1E6BFF, 0xFF8E3BFF, 0xFFFF3D7F, 0xFF16C2D5});
        } else if (s.equals("nebula")) {
            live(0xFF0A0A14, 0xFF140A1F, new int[]{0xFFFF7A18, 0xFFE0218A, 0xFF5B2BFF, 0xFF00C2A8});
        } else if (s.equals("grad:night")) {
            layer.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF0B1026, 0xFF1B2250, 0xFF3A1C55}));
        } else if (s.equals("grad:dusk")) {
            layer.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF141028, 0xFF4B2378, 0xFFD9653B}));
        } else if (s.startsWith("photo:")) {
            photo(new File(Hub.imagesDir(), s.substring(6)));
        } else if (s.equals("video")) {
            video(Hub.videoWallpaperFile());
        } else {
            apply("aurora");
        }
    }

    private void clear() {
        for (ObjectAnimator a : anims) a.cancel();
        anims.clear();
        if (mp != null) {
            mp.release();
            mp = null;
        }
        tex = null;
        layer.removeAllViews();
        layer.setBackground(null);
    }

    // ---------------------------------------------------------------- live terbina

    private static Bitmap blob(int color) {
        int n = 160;
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(n / 2f, n / 2f, n / 2f, new int[]{(color & 0xFFFFFF) | 0xB0000000,
                (color & 0xFFFFFF) | 0x40000000, color & 0xFFFFFF}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        new Canvas(b).drawCircle(n / 2f, n / 2f, n / 2f, p);
        return b;
    }

    private void live(int top, int bottom, int[] colors) {
        layer.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{top, bottom}));
        float[][] pos = {{-0.15f, -0.25f}, {0.45f, -0.1f}, {0.05f, 0.35f}, {0.55f, 0.4f}};
        for (int i = 0; i < colors.length; i++) {
            ImageView v = new ImageView(getContext());
            v.setImageBitmap(blob(colors[i]));
            v.setScaleType(ImageView.ScaleType.FIT_XY);
            int size = (int) (S.w * (0.62f + 0.08f * i));
            LayoutParams lp = new LayoutParams(size, size);
            lp.leftMargin = (int) (S.w * pos[i][0]);
            lp.topMargin = (int) (S.h * pos[i][1]);
            layer.addView(v, lp);
            float dx = S.w * (i % 2 == 0 ? 0.18f : -0.16f), dy = S.h * (i < 2 ? 0.22f : -0.2f);
            ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(v,
                    PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0, dx),
                    PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0, dy),
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.25f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.25f));
            a.setDuration(14000 + i * 4000);
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setRepeatMode(ValueAnimator.REVERSE);
            a.setInterpolator(new AccelerateDecelerateInterpolator());
            anims.add(a);
            if (Store.fx(getContext()) && !paused) a.start();
        }
    }

    // ---------------------------------------------------------------- gambar

    private void photo(final File f) {
        final ImageView v = new ImageView(getContext());
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setAlpha(0f);
        layer.addView(v, new LayoutParams(-1, -1));
        final String mySpec = spec;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap b = f.exists() ? ImageViewerActivity.decode(f, S.w, S.h) : null;
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (!mySpec.equals(spec)) return;
                        if (b == null) {
                            spec = "";
                            apply("aurora");
                            return;
                        }
                        v.setImageBitmap(b);
                        v.animate().alpha(1f).setDuration(500).start();
                    }
                });
            }
        }).start();
    }

    // ---------------------------------------------------------------- video

    private void video(final File f) {
        if (!f.exists()) {
            spec = "";
            apply("aurora");
            return;
        }
        tex = new TextureView(getContext());
        tex.setAlpha(0f);
        layer.addView(tex, new LayoutParams(-1, -1));
        tex.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                try {
                    mp = new MediaPlayer();
                    mp.setDataSource(f.getPath());
                    mp.setSurface(new Surface(st));
                    mp.setLooping(true);
                    mp.setVolume(0f, 0f);
                    mp.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                        @Override
                        public void onVideoSizeChanged(MediaPlayer m, int vw, int vh) {
                            videoW = vw;
                            videoH = vh;
                            crop();
                        }
                    });
                    mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                        @Override
                        public void onPrepared(MediaPlayer m) {
                            if (!paused) m.start();
                            tex.animate().alpha(1f).setDuration(600).start();
                        }
                    });
                    mp.prepareAsync();
                } catch (Exception e) {
                    spec = "";
                    apply("aurora");
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                crop();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                if (mp != null) {
                    mp.release();
                    mp = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {}
        });
    }

    /** Video isi penuh skrin (crop tengah) tanpa herot. */
    private void crop() {
        if (tex == null || videoW == 0 || tex.getWidth() == 0) return;
        float vw = tex.getWidth(), vh = tex.getHeight();
        float s = Math.max(vw / videoW, vh / videoH);
        Matrix m = new Matrix();
        m.setScale(videoW * s / vw, videoH * s / vh, vw / 2f, vh / 2f);
        tex.setTransform(m);
    }

    // ---------------------------------------------------------------- kitar hayat

    void pause() {
        paused = true;
        for (ObjectAnimator a : anims) a.pause();
        if (mp != null && mp.isPlaying()) mp.pause();
    }

    void resume() {
        paused = false;
        boolean fx = Store.fx(getContext());
        for (ObjectAnimator a : anims) {
            if (!fx) a.cancel();
            else if (a.isPaused()) a.resume();
            else if (!a.isStarted()) a.start();
        }
        if (mp != null) {
            try {
                mp.start();
            } catch (Exception ignored) {
            }
        }
    }

    void parallax(float nx, float ny) {
        if (!Store.fx(getContext())) return;
        layer.animate().translationX(-nx * S.px(36)).translationY(-ny * S.px(22)).setDuration(900).start();
    }

    /** Untuk dipaparkan sebagai pratonton kecil di Tetapan. */
    static GradientDrawable preview(String spec) {
        switch (spec) {
            case "aurora": return new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF1E6BFF, 0xFF8E3BFF, 0xFFFF3D7F});
            case "nebula": return new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFFF7A18, 0xFFE0218A, 0xFF5B2BFF});
            case "grad:night": return new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF0B1026, 0xFF1B2250, 0xFF3A1C55});
            case "grad:dusk": return new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF141028, 0xFF4B2378, 0xFFD9653B});
            default: return new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF222630, 0xFF111318});
        }
    }
}
