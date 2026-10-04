package com.aqil.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Tetapan & data kecil yang disimpan (SharedPreferences). */
final class Store {
    static final String DEFAULT_PLAYLIST = "https://iptv-org.github.io/iptv/countries/my.m3u";
    private static final SecureRandom RNG = new SecureRandom();

    private Store() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("aqil", Context.MODE_PRIVATE);
    }

    static String playlist(Context c) {
        return p(c).getString("playlist", DEFAULT_PLAYLIST);
    }

    static void setPlaylist(Context c, String url) {
        p(c).edit().putString("playlist", url == null || url.isEmpty() ? DEFAULT_PLAYLIST : url).apply();
    }

    static int lastChannel(Context c) {
        return p(c).getInt("lastCh", 0);
    }

    static void setLastChannel(Context c, int i) {
        p(c).edit().putInt("lastCh", i).apply();
    }

    static boolean fx(Context c) {
        return p(c).getBoolean("fx", true);
    }

    static void setFx(Context c, boolean on) {
        p(c).edit().putBoolean("fx", on).apply();
    }

    static List<String> favorites(Context c) {
        String s = p(c).getString("favs", "");
        List<String> out = new ArrayList<>();
        for (String x : s.split("\n")) if (!x.isEmpty()) out.add(x);
        return out;
    }

    static boolean isFavorite(Context c, String pkg) {
        return favorites(c).contains(pkg);
    }

    static void toggleFavorite(Context c, String pkg) {
        List<String> f = favorites(c);
        if (!f.remove(pkg)) f.add(pkg);
        StringBuilder sb = new StringBuilder();
        for (String x : f) sb.append(x).append('\n');
        p(c).edit().putString("favs", sb.toString()).apply();
    }

    // ---- pairing ----

    static synchronized String pin(Context c) {
        String pin = p(c).getString("pin", null);
        if (pin == null) pin = newPin(c);
        return pin;
    }

    /** PIN baharu; semua telefon yang dah dipadankan perlu masukkan PIN semula. */
    static synchronized String newPin(Context c) {
        String pin = String.format("%04d", RNG.nextInt(10000));
        p(c).edit().putString("pin", pin).putStringSet("tokens", new HashSet<String>()).apply();
        return pin;
    }

    static synchronized String addToken(Context c) {
        byte[] b = new byte[16];
        RNG.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        Set<String> t = new HashSet<>(p(c).getStringSet("tokens", new HashSet<String>()));
        t.add(sb.toString());
        p(c).edit().putStringSet("tokens", t).apply();
        return sb.toString();
    }

    static synchronized boolean validToken(Context c, String t) {
        return t != null && p(c).getStringSet("tokens", new HashSet<String>()).contains(t);
    }
}
