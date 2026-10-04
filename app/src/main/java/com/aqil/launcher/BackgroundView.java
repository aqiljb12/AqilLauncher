package com.aqil.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.view.View;

import java.util.Random;

/** Latar "aurora" hidup: orb gradien bergerak, zarah terapung, kesan parallax bila fokus bergerak. */
final class BackgroundView extends View {
    private static final int[][] ORBS = {
            {0xFF0A84FF, 0xFF5E5CE6}, {0xFFBF5AF2, 0xFFFF375F}, {0xFF30D5C8, 0xFF0A84FF},
            {0xFFFF9F0A, 0xFFFF375F}, {0xFF5E5CE6, 0xFFBF5AF2}};
    private final Paint base = new Paint(), orb = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG), shade = new Paint();
    private final Shader[] orbShader = new Shader[ORBS.length];
    private final float[] ox = new float[ORBS.length], oy = new float[ORBS.length], osp = new float[ORBS.length],
            oph = new float[ORBS.length], orad = new float[ORBS.length], odepth = new float[ORBS.length];
    private final float[] px, py, pv, ps;
    private Bitmap wall;
    private float tx, ty, cx, cy;
    private boolean animate = true;
    private final long t0 = System.nanoTime();
    private final Rect src = new Rect(), dst = new Rect();

    BackgroundView(Context c) {
        super(c);
        Random r = new Random(7);
        for (int i = 0; i < ORBS.length; i++) {
            ox[i] = 0.1f + r.nextFloat() * 0.8f;
            oy[i] = 0.1f + r.nextFloat() * 0.8f;
            osp[i] = 0.08f + r.nextFloat() * 0.12f;
            oph[i] = r.nextFloat() * 6.28f;
            odepth[i] = 12f + i * 10f;
        }
        px = new float[48];
        py = new float[48];
        pv = new float[48];
        ps = new float[48];
        for (int i = 0; i < px.length; i++) {
            px[i] = r.nextFloat();
            py[i] = r.nextFloat();
            pv[i] = 0.004f + r.nextFloat() * 0.012f;
            ps[i] = 0.8f + r.nextFloat() * 2.2f;
        }
        orb.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    void setAnimated(boolean on) {
        animate = on;
        invalidate();
    }

    /** Wallpaper (dikecilkan dulu supaya jadi kabur bila dibesarkan). */
    void setWallpaper(Bitmap b) {
        if (b == null) {
            wall = null;
        } else {
            int w = 64, h = Math.max(1, b.getHeight() * 64 / b.getWidth());
            wall = Bitmap.createScaledBitmap(b, w, h, true);
        }
        invalidate();
    }

    void setParallax(float nx, float ny) {
        tx = nx;
        ty = ny;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        base.setShader(new LinearGradient(0, 0, w, h, 0xFF070B1A, 0xFF150C2B, Shader.TileMode.CLAMP));
        shade.setShader(new RadialGradient(w / 2f, h / 2f, Math.max(w, h) * 0.75f, 0x00000000, 0xAA000000, Shader.TileMode.CLAMP));
        for (int i = 0; i < ORBS.length; i++) {
            orad[i] = Math.max(w, h) * (0.28f + 0.05f * i);
            orbShader[i] = new RadialGradient(0, 0, orad[i], new int[]{ORBS[i][0] & 0x00FFFFFF | 0x99000000,
                    ORBS[i][1] & 0x00FFFFFF | 0x33000000, 0x00000000}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float t = (System.nanoTime() - t0) / 1e9f;
        cx += (tx - cx) * 0.06f;
        cy += (ty - cy) * 0.06f;
        c.drawRect(0, 0, w, h, base);
        if (wall != null) {
            src.set(0, 0, wall.getWidth(), wall.getHeight());
            // isi skrin (crop tengah)
            float s = Math.max(w / (float) wall.getWidth(), h / (float) wall.getHeight());
            int dw = (int) (wall.getWidth() * s), dh = (int) (wall.getHeight() * s);
            dst.set((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2);
            c.drawBitmap(wall, src, dst, bmp);
            c.drawColor(0x88000000);
        }
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < ORBS.length; i++) {
            float x = w * (ox[i] + 0.22f * (float) Math.sin(t * osp[i] + oph[i])) - cx * odepth[i] * d;
            float y = h * (oy[i] + 0.20f * (float) Math.cos(t * osp[i] * 1.3f + oph[i])) - cy * odepth[i] * d;
            orb.setShader(orbShader[i]);
            c.save();
            c.translate(x, y);
            c.drawCircle(0, 0, orad[i], orb);
            c.restore();
        }
        dot.setColor(0xFFFFFFFF);
        for (int i = 0; i < px.length; i++) {
            float depth = ps[i];
            float x = ((px[i] + t * pv[i]) % 1f) * w - cx * depth * 14 * d;
            float y = ((py[i] - t * pv[i] * 0.6f + 1f) % 1f) * h - cy * depth * 14 * d;
            dot.setAlpha((int) (40 + 50 * (0.5f + 0.5f * Math.sin(t * 0.7f + i))));
            c.drawCircle(x, y, depth * d, dot);
        }
        c.drawRect(0, 0, w, h, shade);
        if (animate) postInvalidateDelayed(33);
    }
}
