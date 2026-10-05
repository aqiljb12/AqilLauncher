package com.aqil.launcher;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
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
    private Surface surface;
    private TextureView tex;
    /** Spec terakhir yang dipohon, untuk dipasang semula jika paparan dilekat semula selepas dilepaskan. */
    private String lastSpec;
    private String spec = "";
    private boolean paused;
    /** Ditahan: halaman berat (Live TV / Filem) dipaparkan atau launcher tersembunyi → tiada animasi, video dilepas. */
    private boolean held;
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
        lastSpec = s;
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
        releasePlayer();
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

    private static final float[][] POS = {{-0.15f, -0.25f}, {0.45f, -0.1f}, {0.05f, 0.35f}, {0.55f, 0.4f}};

    private void live(int top, int bottom, int[] colors) {
        if (!Perf.motion(getContext())) {
            staticLive(top, bottom, colors);
            return;
        }
        layer.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{top, bottom}));
        float[][] pos = POS;
        boolean lite = Perf.tier(getContext()) < Perf.HIGH;
        for (int i = 0; i < colors.length; i++) {
            if (lite && (i == 1 || i == 2)) continue; // tahap Sederhana: 2 gumpalan (separuh kerja GPU)
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
        }
        sync();
    }

    /**
     * Tahap Ringan / animasi mati: lukis gradien + gumpalan SEKALI ke bitmap kecil (1/3 resolusi – gradien lembut
     * tidak nampak beza) dan papar sebagai satu imej legap. Tiada adunan alfa berlapis setiap bingkai.
     */
    private void staticLive(int top, int bottom, int[] colors) {
        int bw = Math.max(320, S.w / 3), bh = Math.max(180, S.h / 3);
        try {
            Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new LinearGradient(0, 0, bw, bh, top, bottom, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, bw, bh, p);
            for (int i = 0; i < colors.length && i < POS.length; i++) {
                float size = bw * (0.62f + 0.08f * i), r = size / 2f;
                float cx = bw * POS[i][0] + r, cy = bh * POS[i][1] + r; // sama seperti susun atur gumpalan bergerak
                p.setShader(new RadialGradient(cx, cy, r, new int[]{(colors[i] & 0xFFFFFF) | 0xB0000000,
                        (colors[i] & 0xFFFFFF) | 0x40000000, colors[i] & 0xFFFFFF}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
                cv.drawCircle(cx, cy, r, p);
            }
            BitmapDrawable d = new BitmapDrawable(getResources(), b);
            d.setFilterBitmap(true);
            layer.setBackground(d);
        } catch (Throwable e) {
            layer.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{top, bottom}));
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
        if (held) {
            posterFrame(f);
            return;
        }
        tex = new TextureView(getContext());
        final TextureView myTex = tex;
        tex.setAlpha(0f);
        layer.addView(tex, new LayoutParams(-1, -1));
        tex.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                if (tex != myTex) return; // wallpaper sudah ditukar
                try {
                    releasePlayer();
                    mp = new MediaPlayer();
                    mp.setDataSource(f.getPath());
                    surface = new Surface(st);
                    mp.setSurface(surface);
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
                            if (m != mp) return;
                            if (!paused && !held) m.start();
                            myTex.animate().alpha(1f).setDuration(600).withEndAction(new Runnable() {
                                @Override
                                public void run() {
                                    if (tex == myTex) dropSnaps(); // bingkai pegun di bawah tidak diperlukan lagi
                                }
                            }).start();
                        }
                    });
                    mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                        @Override
                        public boolean onError(MediaPlayer m, int what, int extra) {
                            // video tak boleh dimainkan (codec dsb.): kembali ke wallpaper live terbina
                            if (m == mp) post(new Runnable() {
                                @Override
                                public void run() {
                                    if ("video".equals(spec)) {
                                        spec = "";
                                        apply("aurora");
                                    }
                                }
                            });
                            return true;
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
                if (tex == myTex || tex == null) releasePlayer();
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

    private void releasePlayer() {
        if (mp != null) {
            try {
                mp.release();
            } catch (Exception ignored) {
            }
            mp = null;
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
    }

    // ---------------------------------------------------------------- kitar hayat

    /**
     * Animator tanpa had + MediaPlayer mesti dihentikan bila paparan dicabut (cth. aktiviti dimusnahkan),
     * jika tidak ia terus berjalan dan memegang aktiviti lama (kebocoran memori + CPU di latar).
     */
    @Override
    protected void onDetachedFromWindow() {
        for (ObjectAnimator x : anims) x.cancel();
        anims.clear();
        releasePlayer();
        spec = ""; // dilekat semula → apply(lastSpec) bina semula
        super.onDetachedFromWindow();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (spec.isEmpty() && lastSpec != null) apply(lastSpec);
    }

    void pause() {
        paused = true;
        sync();
    }

    void resume() {
        paused = false;
        sync();
    }

    /**
     * Tahan wallpaper semasa halaman berat dipaparkan atau launcher tersembunyi (Live TV skrin penuh, apl lain).
     * Wallpaper video DILEPASKAN (bukan dijeda) supaya dekoder perkakasan bebas untuk siaran – banyak TV box
     * hanya ada 1–2 dekoder; jika dipegang wallpaper, Live TV terpaksa guna dekoder perisian yang perlahan.
     * Bingkai terakhir dipaparkan sebagai gambar pegun supaya tiada kelipan.
     */
    void setHold(boolean h) {
        if (h == held) return;
        held = h;
        if (h) freezeVideo();
        else if ("video".equals(spec) && tex == null) video(Hub.videoWallpaperFile());
        sync();
    }

    private void sync() {
        boolean run = !paused && !held && Perf.motion(getContext());
        for (ObjectAnimator a : anims) {
            if (run) {
                if (a.isPaused()) a.resume();
                else if (!a.isStarted()) a.start();
            } else if (a.isStarted()) {
                a.pause();
            }
        }
        if (mp != null) {
            try {
                if (paused || held) {
                    if (mp.isPlaying()) mp.pause();
                } else if (!mp.isPlaying()) {
                    mp.start();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void freezeVideo() {
        if (tex == null && mp == null) return;
        Bitmap b = null;
        try {
            // getBitmap() abaikan transform crop → minta bitmap ikut nisbah video, kemudian CENTER_CROP
            if (tex != null && tex.isAvailable() && videoW > 0 && videoH > 0) {
                int bw = Math.min(960, videoW);
                b = tex.getBitmap(bw, Math.max(1, bw * videoH / videoW));
            }
        } catch (Throwable ignored) {
        }
        releasePlayer();
        if (tex != null) {
            layer.removeView(tex);
            tex = null;
        }
        if (b != null) addSnap(b);
    }

    /** Bingkai pertama video sebagai gambar pegun (bila wallpaper dipasang semasa ditahan). */
    private void posterFrame(final File f) {
        final String mySpec = spec;
        new Thread(new Runnable() {
            @Override
            public void run() {
                Bitmap b = null;
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    r.setDataSource(f.getPath());
                    b = r.getFrameAtTime(0);
                } catch (Throwable ignored) {
                } finally {
                    try {
                        r.release();
                    } catch (Throwable ignored) {
                    }
                }
                final Bitmap fb = b;
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (fb != null && mySpec.equals(spec) && tex == null) addSnap(fb);
                    }
                });
            }
        }, "wall-frame").start();
    }

    private void addSnap(Bitmap b) {
        ImageView snap = new ImageView(getContext());
        snap.setScaleType(ImageView.ScaleType.CENTER_CROP);
        snap.setImageBitmap(b);
        snap.setTag("snap");
        layer.addView(snap, 0, new LayoutParams(-1, -1));
    }

    private void dropSnaps() {
        for (int i = layer.getChildCount() - 1; i >= 0; i--) {
            if ("snap".equals(layer.getChildAt(i).getTag())) layer.removeViewAt(i);
        }
    }

    void parallax(float nx, float ny) {
        if (!Perf.motion(getContext()) || held) return;
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
