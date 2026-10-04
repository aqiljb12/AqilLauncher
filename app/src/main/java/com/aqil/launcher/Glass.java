package com.aqil.launcher;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

/** Lukisan "liquid glass" gaya iOS: kaca lut sinar, kilau atas, bunga api tepi dan sinaran bergerak. */
final class Glass {
    private static final Paint P = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path CLIP = new Path();
    private static final RectF R = new RectF();

    private Glass() {}

    /**
     * @param focus 0..1 seberapa difokus
     * @param tintA warna atas (0 = kaca putih), tintB warna bawah
     * @param shine -0.5..1.5 kedudukan jalur sinaran, di luar 0..1 = tiada
     */
    static void plate(Canvas c, float d, float l, float t, float r, float b, float rad,
                      float focus, int tintA, int tintB, float shine) {
        float w = r - l, h = b - t;
        // pendar luar
        if (focus > 0.01f) {
            for (int i = 3; i >= 1; i--) {
                P.setShader(null);
                P.setStyle(Paint.Style.FILL);
                P.setColor(((int) (focus * 16) << 24) | (tintA != 0 ? (tintA & 0xFFFFFF) : 0xFFFFFF));
                R.set(l - i * 5 * d, t - i * 5 * d, r + i * 5 * d, b + i * 5 * d);
                c.drawRoundRect(R, rad + i * 5 * d, rad + i * 5 * d, P);
            }
        }
        R.set(l, t, r, b);
        // isi kaca
        int a, bt;
        if (tintA == 0) {
            a = argb(0x40 + (int) (focus * 0x38), 255, 255, 255);
            bt = argb(0x1A + (int) (focus * 0x20), 255, 255, 255);
        } else {
            a = (tintA & 0xFFFFFF) | (0xD8 << 24);
            bt = (tintB & 0xFFFFFF) | (0xD8 << 24);
        }
        P.setStyle(Paint.Style.FILL);
        P.setShader(new LinearGradient(0, t, 0, b, a, bt, Shader.TileMode.CLAMP));
        c.drawRoundRect(R, rad, rad, P);

        // kilau separuh atas + sinaran
        c.save();
        CLIP.reset();
        CLIP.addRoundRect(R, rad, rad, Path.Direction.CW);
        c.clipPath(CLIP);
        P.setShader(new LinearGradient(0, t, 0, t + h * 0.55f, 0x50FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(l, t, r, t + h * 0.55f, P);
        if (shine > -0.4f && shine < 1.4f) {
            float x = l + shine * w;
            float bw = w * 0.45f;
            P.setShader(new LinearGradient(x - bw, 0, x + bw, 0,
                    new int[]{0x00FFFFFF, 0x55FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(l, t, r, b, P);
        }
        c.restore();

        // garis tepi: terang di atas, malap di bawah
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeWidth(1.6f * d);
        P.setShader(new LinearGradient(0, t, 0, b, argb(0xA0 + (int) (focus * 0x5F), 255, 255, 255),
                argb(0x28 + (int) (focus * 0x40), 255, 255, 255), Shader.TileMode.CLAMP));
        R.inset(0.8f * d, 0.8f * d);
        c.drawRoundRect(R, rad, rad, P);
        P.setShader(null);
        P.setStyle(Paint.Style.FILL);
    }

    static int argb(int a, int r, int g, int b) {
        return (Math.min(255, a) << 24) | (r << 16) | (g << 8) | b;
    }
}
