package com.aqil.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Senarai apl yang boleh dilancar + ikon, banner TV dan warna dominan (untuk kad berwarna). */
final class Apps {
    static final class A {
        String pkg, label;
        Drawable icon, banner;
        Intent launch;
        int color;
    }

    static volatile List<A> all = new ArrayList<>();
    /** Apl popular yang diletak dalam Kegemaran pada kali pertama (ikut susunan). */
    private static final String[] POPULAR = {"youtube", "netflix", "disney", "prime video", "astro", "tiktok", "iqiyi", "viu", "spotify"};

    private Apps() {}

    static void load(final Context c, final Runnable done) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                PackageManager pm = c.getPackageManager();
                Map<String, A> map = new HashMap<>();
                for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
                    for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                        String pkg = ri.activityInfo.packageName;
                        if (pkg.equals(c.getPackageName()) || map.containsKey(pkg)) continue;
                        A a = new A();
                        a.pkg = pkg;
                        a.label = ri.loadLabel(pm).toString();
                        try {
                            a.icon = ri.loadIcon(pm);
                            a.banner = ri.activityInfo.loadBanner(pm);
                            if (a.banner == null) a.banner = pm.getApplicationBanner(pkg);
                        } catch (Exception ignored) {
                        }
                        a.launch = pm.getLeanbackLaunchIntentForPackage(pkg);
                        if (a.launch == null) a.launch = pm.getLaunchIntentForPackage(pkg);
                        if (a.launch == null) continue;
                        a.color = dominant(a.icon);
                        map.put(pkg, a);
                    }
                }
                List<A> list = new ArrayList<>(map.values());
                Collections.sort(list, new Comparator<A>() {
                    @Override
                    public int compare(A x, A y) {
                        return x.label.compareToIgnoreCase(y.label);
                    }
                });
                all = list;
                if (!Store.favoritesInitialised(c)) Store.setFavorites(c, popular(6));
                if (done != null) Hub.main.post(done);
            }
        }, "apps").start();
    }

    static A find(String pkg) {
        for (A a : all) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    static List<String> popular(int max) {
        List<String> out = new ArrayList<>();
        for (String key : POPULAR) {
            for (A a : all) {
                if (out.size() >= max) return out;
                if (a.label.toLowerCase(Locale.ROOT).contains(key) && !out.contains(a.pkg)) {
                    out.add(a.pkg);
                    break;
                }
            }
        }
        for (A a : all) {
            if (out.size() >= max) break;
            if (!out.contains(a.pkg)) out.add(a.pkg);
        }
        return out;
    }

    static List<A> favorites(Context c) {
        List<A> out = new ArrayList<>();
        for (String p : Store.favorites(c)) {
            A a = find(p);
            if (a != null) out.add(a);
        }
        return out;
    }

    /** Warna paling "hidup" dari ikon, digelapkan sedikit untuk latar kad. */
    static int dominant(Drawable d) {
        if (d == null) return 0xFF2C3140;
        try {
            Bitmap b = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            d.setBounds(0, 0, 24, 24);
            d.draw(cv);
            float[] hsv = new float[3];
            float best = -1;
            int pick = 0xFF2C3140;
            long r = 0, g = 0, bl = 0, n = 0;
            for (int y = 0; y < 24; y++) {
                for (int x = 0; x < 24; x++) {
                    int p = b.getPixel(x, y);
                    if (Color.alpha(p) < 200) continue;
                    Color.colorToHSV(p, hsv);
                    r += Color.red(p);
                    g += Color.green(p);
                    bl += Color.blue(p);
                    n++;
                    float score = hsv[1] * hsv[2];
                    if (score > best) {
                        best = score;
                        pick = p;
                    }
                }
            }
            b.recycle();
            if (best < 0.15f && n > 0) pick = Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
            Color.colorToHSV(pick, hsv);
            hsv[2] = Math.min(hsv[2], 0.62f);
            return Color.HSVToColor(hsv);
        } catch (Exception e) {
            return 0xFF2C3140;
        }
    }
}
