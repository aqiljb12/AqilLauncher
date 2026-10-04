package com.aqil.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Papar gambar dari telefon: peralihan flip 3D, zum perlahan (Ken Burns), tayangan slaid. */
public class ImageViewerActivity extends BaseActivity {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private ImageView front, back;
    private TextView caption;
    private List<File> files = new ArrayList<>();
    private int index = -1;
    private int gen;
    private boolean slideshow;
    private float d;
    private final Runnable next = new Runnable() {
        @Override
        public void run() {
            if (slideshow && files.size() > 1) show(index + 1, 1);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = U.dp(this, 1);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        back = newImage();
        front = newImage();
        root.addView(back);
        root.addView(front);
        caption = new TextView(this) {
            @Override
            protected void onDraw(android.graphics.Canvas c) {
                Glass.plate(c, d, 0, 0, getWidth(), getHeight(), getHeight() / 2f, 0f, 0, 0, -1f);
                super.onDraw(c);
            }
        };
        caption.setTextColor(Color.WHITE);
        caption.setTextSize(15);
        caption.setPadding((int) (22 * d), (int) (10 * d), (int) (22 * d), (int) (10 * d));
        caption.setAlpha(0f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = (int) (36 * d);
        root.addView(caption, lp);
        setContentView(root);
        root.setFocusable(true);
        root.requestFocus();
        handle(true);
    }

    private ImageView newImage() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setCameraDistance(U.dp(this, 1600));
        v.setAlpha(0f);
        return v;
    }

    @Override
    public void onNewIntent(android.content.Intent i) {
        super.onNewIntent(i);
        handle(false);
    }

    private void handle(boolean first) {
        files = Hub.images();
        if (files.isEmpty()) {
            Toast.makeText(this, "Tiada gambar", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        String name = getIntent().getStringExtra("name");
        slideshow = getIntent().getBooleanExtra("slideshow", false);
        int start = 0;
        if (name != null) {
            for (int i = 0; i < files.size(); i++) if (files.get(i).getName().equals(name)) start = i;
        }
        index = -1;
        show(start, 0);
    }

    private void show(int i, final int dir) {
        final int n = files.size();
        final int idx = ((i % n) + n) % n;
        final int g = ++gen;
        final File f = files.get(idx);
        final int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        IO.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bm = decode(f, w, h);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (g == gen && bm != null) swap(bm, idx, dir);
                    }
                });
            }
        });
    }

    private void swap(Bitmap bm, int idx, int dir) {
        index = idx;
        ImageView out = front, in = back;
        front = in;
        back = out;
        in.setImageBitmap(bm);
        in.animate().cancel();
        out.animate().cancel();
        in.setScaleX(1f);
        in.setScaleY(1f);
        if (dir == 0 || out.getAlpha() < 0.1f) {
            // pertama kali: zum masuk
            out.setAlpha(0f);
            in.setAlpha(0f);
            in.setRotationY(0);
            in.setScaleX(1.12f);
            in.setScaleY(1.12f);
            in.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(600).setInterpolator(new DecelerateInterpolator()).start();
        } else {
            // flip 3D: keluar berpusing, masuk dari belakang
            float s = dir > 0 ? 1f : -1f;
            out.setPivotX(out.getWidth() / 2f);
            in.setPivotX(in.getWidth() / 2f);
            out.setRotationY(0);
            in.setRotationY(70f * s);
            in.setAlpha(0f);
            in.setTranslationX(0);
            out.animate().rotationY(-70f * s).alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(520).start();
            in.animate().rotationY(0f).alpha(1f).setDuration(560).setStartDelay(120).setInterpolator(new DecelerateInterpolator()).start();
        }
        // Ken Burns: zum perlahan
        in.animate().scaleX(1.06f).scaleY(1.06f).setDuration(9000).setStartDelay(700).start();
        caption.setText((idx + 1) + " / " + files.size());
        caption.setAlpha(1f);
        caption.animate().alpha(0f).setStartDelay(2500).setDuration(600).start();
        caption.removeCallbacks(next);
        if (slideshow) caption.postDelayed(next, 7000);
    }

    @Override
    public boolean onKeyDown(int k, KeyEvent e) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                show(index + 1, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                show(index - 1, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MENU:
                menu();
                return true;
            default:
                return super.onKeyDown(k, e);
        }
    }

    private void menu() {
        if (index < 0) return;
        final File f = files.get(index);
        new GlassMenu(this, "Gambar " + (index + 1) + " / " + files.size())
                .add(slideshow ? "Henti tayangan slaid" : "Mula tayangan slaid", new Runnable() {
                    @Override
                    public void run() {
                        slideshow = !slideshow;
                        caption.removeCallbacks(next);
                        if (slideshow) caption.postDelayed(next, 2000);
                    }
                })
                .add("Jadikan wallpaper launcher", new Runnable() {
                    @Override
                    public void run() {
                        IO.execute(new Runnable() {
                            @Override
                            public void run() {
                                try (OutputStream o = new FileOutputStream(Hub.wallpaperFile())) {
                                    decode(f, 1920, 1080).compress(Bitmap.CompressFormat.JPEG, 90, o);
                                } catch (Exception ignored) {
                                }
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        Toast.makeText(ImageViewerActivity.this, "Wallpaper ditetapkan", Toast.LENGTH_SHORT).show();
                                    }
                                });
                            }
                        });
                    }
                })
                .add("Padam gambar ini", new Runnable() {
                    @Override
                    public void run() {
                        f.delete();
                        handle(false);
                    }
                })
                .show();
    }

    @Override
    protected void onDestroy() {
        caption.removeCallbacks(next);
        super.onDestroy();
    }

    /** Dekod gambar dengan saiz hampir (maxW x maxH), putar ikut EXIF. */
    static Bitmap decode(File f, int maxW, int maxH) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int s = 1;
            while (o.outWidth / (s * 2) >= maxW && o.outHeight / (s * 2) >= maxH) s *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = s;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
            if (b == null) return null;
            int rot = 0;
            try {
                int ori = new android.media.ExifInterface(f.getPath()).getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION, 1);
                rot = ori == 6 ? 90 : ori == 3 ? 180 : ori == 8 ? 270 : 0;
            } catch (Exception ignored) {
            }
            if (rot != 0) {
                Matrix m = new Matrix();
                m.postRotate(rot);
                b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            }
            return b;
        } catch (OutOfMemoryError e) {
            return null;
        }
    }
}
