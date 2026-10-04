package com.aqil.launcher;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sokongan akaun IPTV "Xtream Codes" (format yang sama dengan IPTV Smarters / TiviMate):
 * Server (DNS) + Username + Password → senarai saluran langsung, kategori, logo dan EPG ringkas.
 */
final class Xtream {
    /** Maklumat akaun terakhir (untuk paparan). */
    static volatile String status = "", expiry = "", user = "", server = "";
    static volatile int maxConnections, activeConnections;

    private Xtream() {}

    /** Betulkan alamat server: tambah http://, buang "/" & "/player_api.php" di hujung. */
    static String normalise(String s) {
        s = s.trim();
        if (!s.contains("://")) s = "http://" + s;
        int q = s.indexOf("/player_api.php");
        if (q > 0) s = s.substring(0, q);
        q = s.indexOf("/get.php");
        if (q > 0) s = s.substring(0, q);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String api(String[] x, String action) throws Exception {
        return x[0] + "/player_api.php?username=" + URLEncoder.encode(x[1], "UTF-8")
                + "&password=" + URLEncoder.encode(x[2], "UTF-8") + (action == null ? "" : "&action=" + action);
    }

    /** Uji log masuk; pulangkan null jika berjaya, atau mesej ralat. */
    /** Pautan yang SELALU disalah anggap sebagai server: kod Downloader, pemendek URL, fail APK. */
    private static final String[] NOT_SERVER = {"aftv.news", "aftvnews.com", "bit.ly", "tinyurl.com", "t.me/", ".apk"};
    static final String WRONG_SERVER = "Alamat ini bukan server IPTV. Pautan aftv.news / bit.ly ialah pautan muat turun APK, "
            + "bukan server. Guna alamat \"DNS\" Smarters / Xtream (cth http://namaserver.com:8080).";

    static String login(Context c, String server, String u, String p) {
        String low = server.toLowerCase(Locale.ROOT);
        for (String bad : NOT_SERVER) if (low.contains(bad)) return WRONG_SERVER;
        String[] x = {normalise(server), u.trim(), p.trim()};
        try {
            String body = get(api(x, null)).trim();
            // server Xtream sebenar sentiasa pulangkan JSON; halaman web (<!DOCTYPE …) = alamat salah
            if (!body.startsWith("{")) return WRONG_SERVER;
            JSONObject o = new JSONObject(body);
            JSONObject ui = o.optJSONObject("user_info");
            if (ui == null || ui.optInt("auth", 0) == 0) return "Username atau password salah";
            if (!"Active".equalsIgnoreCase(ui.optString("status"))) return "Akaun tidak aktif (" + ui.optString("status") + ")";
            readInfo(x, ui);
            Store.setXtream(c, x[0], x[1], x[2]);
            new File(c.getFilesDir(), "xtream.json").delete();
            return null;
        } catch (Exception e) {
            return "Tak dapat sambung ke server: " + e.getMessage();
        }
    }

    static void logout(Context c) {
        Store.setXtream(c, null, null, null);
        new File(c.getFilesDir(), "xtream.json").delete();
        status = expiry = user = server = "";
    }

    private static void readInfo(String[] x, JSONObject ui) {
        status = ui.optString("status");
        user = x[1];
        server = x[0].replaceFirst("^https?://", "");
        maxConnections = ui.optInt("max_connections", 0);
        activeConnections = ui.optInt("active_cons", 0);
        String exp = ui.optString("exp_date", "");
        if (exp.isEmpty() || exp.equals("null")) expiry = "Tiada had";
        else {
            try {
                expiry = new SimpleDateFormat("d MMM yyyy", new Locale("ms")).format(new Date(Long.parseLong(exp) * 1000));
            } catch (Exception e) {
                expiry = exp;
            }
        }
    }

    /** Muat saluran langsung. refresh=false guna cache jika ada (mula lebih laju). */
    static List<Channel> loadLive(Context c, boolean refresh) throws Exception {
        String[] x = Store.xtream(c);
        if (x == null) return null;
        File cache = new File(c.getFilesDir(), "xtream.json");
        JSONObject data = null;
        Exception netErr = null;
        if (refresh || !cache.exists()) {
            try {
                JSONObject o = new JSONObject(get(api(x, null)));
                JSONObject ui = o.optJSONObject("user_info");
                if (ui == null || ui.optInt("auth", 0) == 0) throw new Exception("Log masuk akaun IPTV gagal");
                readInfo(x, ui);
                String ext = "ts";
                JSONArray fmts = ui.optJSONArray("allowed_output_formats");
                if (fmts != null && fmts.length() > 0) {
                    String all = fmts.toString();
                    ext = all.contains("\"ts\"") ? "ts" : all.contains("m3u8") ? "m3u8" : "ts";
                }
                data = new JSONObject();
                data.put("ext", ext);
                data.put("cats", new JSONArray(get(api(x, "get_live_categories"))));
                data.put("streams", new JSONArray(get(api(x, "get_live_streams"))));
                try (FileOutputStream out = new FileOutputStream(cache)) {
                    out.write(data.toString().getBytes("UTF-8"));
                }
            } catch (Exception e) {
                netErr = e;
            }
        }
        if (data == null && cache.exists()) data = new JSONObject(read(cache));
        if (data == null) throw netErr != null ? netErr : new Exception("Tiada data");

        Map<String, String> cats = new HashMap<>();
        JSONArray ca = data.optJSONArray("cats");
        for (int i = 0; ca != null && i < ca.length(); i++) {
            JSONObject o = ca.getJSONObject(i);
            cats.put(o.optString("category_id"), o.optString("category_name"));
        }
        String ext = data.optString("ext", "ts");
        JSONArray st = data.getJSONArray("streams");
        List<Channel> out = new ArrayList<>(st.length());
        for (int i = 0; i < st.length(); i++) {
            JSONObject o = st.getJSONObject(i);
            int id = o.optInt("stream_id");
            if (id == 0) continue;
            String url = x[0] + "/live/" + x[1] + "/" + x[2] + "/" + id + "." + ext;
            String group = cats.get(o.optString("category_id"));
            Channel ch = new Channel(o.optString("name", "Saluran " + (i + 1)).trim(), url, group == null ? "" : group, o.optString("stream_icon", ""));
            ch.xtId = id;
            out.add(ch);
        }
        return out;
    }

    /** "Sedang: … • Seterusnya: …" dari EPG ringkas panel, atau null. Panggil di thread latar. */
    static String nowNext(Context c, Channel ch) {
        String[] x = Store.xtream(c);
        if (x == null || ch.xtId == 0) return null;
        try {
            JSONObject o = new JSONObject(get(api(x, "get_short_epg") + "&stream_id=" + ch.xtId + "&limit=2"));
            JSONArray l = o.optJSONArray("epg_listings");
            if (l == null || l.length() == 0) return null;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < l.length() && i < 2; i++) {
                JSONObject e = l.getJSONObject(i);
                String t = e.optString("title");
                try {
                    t = new String(Base64.decode(t, Base64.DEFAULT), "UTF-8");
                } catch (Exception ignored) {
                }
                String start = e.optString("start", "");
                String hhmm = start.length() >= 16 ? start.substring(11, 16) : "";
                if (i > 0) sb.append("   •   ");
                sb.append(i == 0 ? "Sedang: " : "Seterusnya " + hhmm + ": ").append(t.trim());
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", Hub.UA);
        try {
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new Exception("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[32768];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                return bo.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    private static String read(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[32768];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        }
    }

    static String summary() {
        if (user.isEmpty()) return "Akaun IPTV";
        return user + " @ " + server + "  •  " + (status.isEmpty() ? "?" : status) + "  •  tamat " + expiry
                + (maxConnections > 0 ? "  •  " + maxConnections + " skrin" : "");
    }
}
