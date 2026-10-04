package com.aqil.launcher;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.WindowManager;

/**
 * Skala reka bentuk: semua saiz ditulis untuk skrin 1920x1080 dan diskala ikut skrin sebenar,
 * jadi susun atur sentiasa muat penuh pada mana-mana TV (720p, 1080p, 4K, density pelik).
 */
final class S {
    static float k = 1f;
    static int w = 1920, h = 1080;

    private S() {}

    static void init(Context c) {
        DisplayMetrics m = new DisplayMetrics();
        ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(m);
        w = Math.max(m.widthPixels, m.heightPixels);
        h = Math.min(m.widthPixels, m.heightPixels);
        k = Math.min(w / 1920f, h / 1080f);
    }

    static int px(float designPx) {
        return Math.round(designPx * k);
    }
}
