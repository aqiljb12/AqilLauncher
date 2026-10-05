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

    // ---- akaun IPTV Xtream Codes (Smarters / TiviMate) ----

    /** {server, user, pass} atau null jika guna senarai M3U. */
    static String[] xtream(Context c) {
        String s = p(c).getString("xtServer", null);
        if (s == null || s.isEmpty()) return null;
        return new String[]{s, p(c).getString("xtUser", ""), p(c).getString("xtPass", "")};
    }

    static void setXtream(Context c, String server, String user, String pass) {
        if (server == null) {
            p(c).edit().remove("xtServer").remove("xtUser").remove("xtPass").putInt("lastCh", 0).apply();
        } else {
            p(c).edit().putString("xtServer", server).putString("xtUser", user).putString("xtPass", pass).putInt("lastCh", 0).apply();
        }
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

    static void setFavorites(Context c, List<String> f) {
        StringBuilder sb = new StringBuilder();
        for (String x : f) sb.append(x).append('\n');
        p(c).edit().putString("favs", sb.toString()).putBoolean("favsInit", true).apply();
    }

    static boolean favoritesInitialised(Context c) {
        return p(c).getBoolean("favsInit", false);
    }

    // ---- baru dibuka ----

    private static final java.util.regex.Pattern PKG = java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");

    static void recordLaunch(Context c, String pkg) {
        if (pkg == null || !PKG.matcher(pkg).matches()) return;
        List<String[]> r = recents(c);
        StringBuilder sb = new StringBuilder(pkg).append('|').append(System.currentTimeMillis()).append('\n');
        int n = 1;
        for (String[] x : r) {
            if (x[0].equals(pkg) || n >= 12) continue;
            sb.append(x[0]).append('|').append(x[1]).append('\n');
            n++;
        }
        p(c).edit().putString("recents", sb.toString()).apply();
    }

    /**
     * [pkg, masa] terbaru dulu. Baris rosak/lama (pakej tak sah, masa bukan nombor atau ≤ 0) diabaikan,
     * jadi data SharedPreferences yang rosak tidak boleh menjatuhkan skrin Home.
     */
    static List<String[]> recents(Context c) {
        List<String[]> out = new ArrayList<>();
        String raw;
        try {
            raw = p(c).getString("recents", "");
        } catch (ClassCastException e) {
            raw = ""; // jenis data lama/berbeza
        }
        if (raw == null) return out;
        for (String line : raw.split("\n")) {
            String[] kv = line.trim().split("\\|");
            if (kv.length != 2 || !PKG.matcher(kv[0]).matches()) continue;
            try {
                if (Long.parseLong(kv[1].trim()) <= 0) continue;
            } catch (NumberFormatException e) {
                continue;
            }
            out.add(new String[]{kv[0], kv[1].trim()});
        }
        return out;
    }

    // ---- wallpaper & cuaca ----

    /** aurora | nebula | grad:night | grad:dusk | photo:NAMA | video */
    static String wallpaper(Context c) {
        return p(c).getString("wall", "aurora");
    }

    static void setWallpaper(Context c, String spec) {
        p(c).edit().putString("wall", spec).apply();
    }

    static String[] weatherPlace(Context c) {
        String s = p(c).getString("wplace", null);
        return s == null ? null : s.split("\\|");
    }

    static void setWeatherPlace(Context c, String name, double lat, double lon) {
        p(c).edit().putString("wplace", name + "|" + lat + "|" + lon).apply();
    }

    /**
     * Kualiti Live TV. false (lalai) = "Pintar": mula terus di HD tetapi boleh turun sekejap bila Internet
     * perlahan (elak tersekat). true = "Tertinggi": paksa resolusi tertinggi walaupun Internet perlahan.
     */
    static boolean maxQuality(Context c) {
        return p(c).getBoolean("maxQ2", false);
    }

    static void setMaxQuality(Context c, boolean on) {
        p(c).edit().putBoolean("maxQ2", on).apply();
    }

    /** Mod strim: true (lalai) = Lancar (penimbal besar, kurang tersekat); false = Cepat (tukar saluran laju). */
    static boolean smooth(Context c) {
        return p(c).getBoolean("smooth", true);
    }

    static void setSmooth(Context c, boolean on) {
        p(c).edit().putBoolean("smooth", on).apply();
    }

    /** Tukar kadar segar skrin ikut siaran (cth 50Hz untuk siaran Malaysia 25/50fps) supaya gerakan tak bergegar. */
    static boolean matchFps(Context c) {
        return p(c).getBoolean("matchFps", true);
    }

    static void setMatchFps(Context c, boolean on) {
        p(c).edit().putBoolean("matchFps", on).apply();
    }

    // ---- prestasi automatik (Perf): had tahap & keputusan ujian tunneling, ditetapkan semula setiap versi baharu ----

    static int perfCap(Context c) {
        return p(c).getInt("perfCap", -1);
    }

    static void setPerfCap(Context c, int cap) {
        p(c).edit().putInt("perfCap", cap).apply();
    }

    /** 1 = disahkan berfungsi, 0 = dalam percubaan, -1 = tidak serasi pada TV ini. */
    static int tunnel(Context c) {
        return p(c).getInt("tun", 0);
    }

    static void setTunnel(Context c, int state) {
        p(c).edit().putInt("tun", state).apply();
    }

    static int tunnelOks(Context c) {
        return p(c).getInt("tunOk", 0);
    }

    static void setTunnelOks(Context c, int n) {
        p(c).edit().putInt("tunOk", n).apply();
    }

    /** Tetapkan semula hasil pengesanan bila versi apl berubah (versi baharu mungkin lebih ringan / dibaiki). */
    static void perfVersion(Context c, int ver) {
        if (p(c).getInt("perfVer", -1) == ver) return;
        p(c).edit().putInt("perfVer", ver).remove("perfCap").remove("tun").remove("tunOk").apply();
    }

    /** Format strim akaun Xtream: "ts" (lalai) atau "m3u8" (HLS). */
    static String xtFormat(Context c) {
        return p(c).getString("xtFmt", "ts");
    }

    static void setXtFormat(Context c, String f) {
        p(c).edit().putString("xtFmt", f).apply();
    }

    static boolean autoSkip(Context c) {
        return p(c).getBoolean("autoSkip", true);
    }

    static void setAutoSkip(Context c, boolean on) {
        p(c).edit().putBoolean("autoSkip", on).apply();
    }

    // ---- Filem & Siri: kedudukan sambung tonton + sejarah ----

    static long vodPos(Context c, String url) {
        return p(c).getLong("pos_" + Integer.toHexString(url.hashCode()), 0);
    }

    /** Simpan kedudukan & tambah ke "Sambung tonton" (paling baru dulu, maks 20). */
    static void saveVod(Context c, String title, String url, String icon, long pos, long dur) {
        boolean finished = dur > 0 && pos > dur - 90_000; // hampir habis = selesai
        p(c).edit().putLong("pos_" + Integer.toHexString(url.hashCode()), finished ? 0 : pos).apply();
        try {
            org.json.JSONArray old = vodHistoryRaw(c), out = new org.json.JSONArray();
            if (!finished && pos > 30_000) {
                out.put(new org.json.JSONObject().put("title", title).put("url", url).put("icon", icon == null ? "" : icon)
                        .put("pos", pos).put("dur", dur).put("t", System.currentTimeMillis()));
            }
            for (int i = 0; i < old.length() && out.length() < 20; i++) {
                org.json.JSONObject o = old.optJSONObject(i);
                if (o != null && !url.equals(o.optString("url"))) out.put(o);
            }
            p(c).edit().putString("vodHist", out.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static org.json.JSONArray vodHistoryRaw(Context c) {
        try {
            return new org.json.JSONArray(p(c).getString("vodHist", "[]"));
        } catch (Exception e) {
            return new org.json.JSONArray(); // data rosak: abaikan
        }
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
