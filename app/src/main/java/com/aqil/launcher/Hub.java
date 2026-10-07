package com.aqil.launcher;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keadaan sepanjang proses: aktiviti paling depan, senarai saluran, gambar. */
final class Hub {
    static Application app;
    static final Handler main = new Handler(Looper.getMainLooper());
    static volatile List<Channel> channels = new ArrayList<>();
    static volatile int playing = -1;
    private static WeakReference<BaseActivity> top = new WeakReference<>(null);
    private static final Pattern ATTR = Pattern.compile("([\\w-]+)=\"([^\"]*)\"");

    private Hub() {}

    static void init(Application a) {
        app = a;
    }

    static BaseActivity top() {
        return top.get();
    }

    static void setTop(BaseActivity a) {
        top = new WeakReference<>(a);
    }

    static void clearTop(BaseActivity a) {
        if (top.get() == a) top = new WeakReference<>(null);
    }

    // ---------- saluran TV ----------

    interface Done {
        void run(int count, String error);
    }

    private static final Object LOCK = new Object();
    private static final long STALE_MS = 12 * 3600_000L;
    /** Senarai baharu dari kemas kini latar, menunggu masa selamat untuk ditukar (bukan semasa menonton). */
    private static volatile List<Channel> pending;
    private static volatile boolean refreshing;
    private static volatile long lastRefreshTry;

    /**
     * Muat senarai saluran di thread lain; panggil balik di thread utama.
     * force=false: guna senarai yang sudah ada / cache storan (serta-merta), kemudian kemas kini di latar jika
     * cache sudah lebih 12 jam. force=true: muat turun semula sekarang (butang "Muat semula", tukar akaun).
     */
    static void loadChannels(final boolean force, final Done done) {
        if (!force && !channels.isEmpty()) {
            finish(done, null);
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                String[] err = new String[1];
                synchronized (LOCK) {
                    if (force || channels.isEmpty()) { // mungkin sudah dimuat oleh panggilan lain semasa menunggu
                        List<Channel> l = build(force, err);
                        if (!l.isEmpty()) {
                            channels = l;
                            pending = null;
                        }
                    }
                }
                finish(done, err[0]);
                if (!force) refreshIfStale();
            }
        }, "playlist").start();
    }

    private static List<Channel> build(boolean refresh, String[] err) {
        if (Store.xtream(app) != null) {
            // akaun Xtream Codes: saluran percuma + saluran dari API panel
            List<Channel> merged = new ArrayList<>(freeChannels(refresh));
            try {
                List<Channel> l = Xtream.loadLive(app, refresh);
                if (l != null) merged.addAll(l);
            } catch (Exception e) {
                err[0] = msg(e);
            }
            return merged;
        }
        File cache = new File(app.getFilesDir(), "playlist.m3u");
        if (refresh || !cache.exists()) {
            try {
                download(Store.playlist(app), cache);
            } catch (Exception e) {
                err[0] = msg(e);
            }
        }
        try {
            if (cache.exists()) return parse(cache);
        } catch (Exception e) {
            err[0] = e.toString();
        }
        return new ArrayList<>();
    }

    private static String msg(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    /** Cache senarai lebih 12 jam → muat turun di latar tanpa ganggu paparan (cuba paling kerap 30 minit sekali). */
    static void refreshIfStale() {
        // jangan muat turun/hurai senarai besar semasa siaran sedang dimainkan (CPU & Internet untuk video dulu)
        if (refreshing || channels.isEmpty() || app == null || playing >= 0) return;
        long now = System.currentTimeMillis();
        if (now - lastRefreshTry < 30 * 60_000L) return;
        File f = new File(app.getFilesDir(), Store.xtream(app) != null ? "xtream.json" : "playlist.m3u");
        if (f.exists() && now - f.lastModified() < STALE_MS) return;
        refreshing = true;
        lastRefreshTry = now;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Channel> l;
                    String[] err = new String[1];
                    synchronized (LOCK) {
                        l = build(true, err);
                    }
                    if (err[0] == null && !l.isEmpty() && sig(l) != sig(channels)) {
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                pending = l;
                                applyPending();
                            }
                        });
                    }
                } finally {
                    refreshing = false;
                }
            }
        }, "playlist-bg").start();
    }

    /**
     * Pasang senarai dari kemas kini latar hanya bila launcher di depan dan tiada siaran dibuka (nombor saluran
     * berubah). Saluran terakhir dipetakan semula ikut URL supaya "Saluran terakhir" kekal betul.
     */
    static void applyPending() {
        List<Channel> l = pending;
        if (l == null) return;
        BaseActivity t = top();
        if (playing >= 0 || !(t instanceof MainActivity)) return;
        pending = null;
        List<Channel> old = channels;
        int last = Store.lastChannel(app);
        if (last >= 0 && last < old.size()) {
            String url = old.get(last).url;
            for (int i = 0; i < l.size(); i++) {
                if (l.get(i).url.equals(url)) {
                    Store.setLastChannel(app, i);
                    break;
                }
            }
        }
        channels = l;
        ((MainActivity) t).channelsChanged();
    }

    private static long sig(List<Channel> l) {
        long h = l.size();
        for (Channel c : l) h = h * 31 + c.url.hashCode() * 17L + (c.name == null ? 0 : c.name.hashCode());
        return h;
    }

    /**
     * Saluran percuma rasmi (RTM TV1/TV2/Berita/Sukan/Okey dll) dari senarai iptv-org Malaysia –
     * sentiasa dipaparkan di atas, walaupun log masuk akaun IPTV. Kumpulan: "Percuma (RTM & lain-lain)".
     */
    private static List<Channel> freeChannels(boolean refresh) {
        File cache = new File(app.getFilesDir(), "free.m3u");
        try {
            if (refresh || !cache.exists()) download(Store.DEFAULT_PLAYLIST, cache);
        } catch (Exception ignored) {
        }
        List<Channel> out = new ArrayList<>();
        try {
            if (cache.exists()) {
                for (Channel c : parse(cache)) {
                    Channel f = new Channel(c.name.replace(" [Geo-blocked]", ""), c.url, "Percuma (RTM & lain-lain)", c.logo);
                    f.ua = c.ua;
                    f.referer = c.referer;
                    f.origin = c.origin;
                    out.add(f);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void finish(final Done done, String err) {
        final int n = channels.size();
        final String fe = n == 0 && err == null ? "Senarai kosong" : (n == 0 ? err : null);
        if (done != null) main.post(new Runnable() {
            @Override
            public void run() {
                done.run(n, fe);
            }
        });
    }

    private static void download(String url, File dest) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", UA);
        if (c.getResponseCode() / 100 != 2) throw new Exception("HTTP " + c.getResponseCode());
        File tmp = new File(dest.getPath() + ".tmp");
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        } finally {
            c.disconnect();
        }
        if (!tmp.renameTo(dest)) throw new Exception("Gagal simpan senarai");
    }

    static List<Channel> parse(File f) throws Exception {
        List<Channel> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            String line, name = null, group = "", logo = "", ua = null, ref = null, origin = null, drmType = null, drmKey = null;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#EXTINF")) {
                    int comma = line.lastIndexOf(',');
                    name = comma >= 0 ? line.substring(comma + 1).trim() : "Saluran";
                    Matcher m = ATTR.matcher(comma >= 0 ? line.substring(0, comma) : line);
                    while (m.find()) {
                        if (m.group(1).equals("group-title")) group = m.group(2);
                        else if (m.group(1).equals("tvg-logo")) logo = m.group(2);
                        else if (m.group(1).equals("http-user-agent") || m.group(1).equals("user-agent")) ua = m.group(2);
                        else if (m.group(1).equals("http-referrer")) ref = m.group(2);
                    }
                } else if (line.startsWith("#EXTVLCOPT:")) {
                    String o = line.substring(11);
                    if (o.startsWith("http-user-agent=")) ua = o.substring(16);
                    else if (o.startsWith("http-referrer=")) ref = o.substring(14);
                    else if (o.startsWith("http-origin=")) origin = o.substring(12);
                } else if (line.startsWith("#KODIPROP:")) {
                    String o = line.substring(10);
                    if (o.startsWith("inputstream.adaptive.license_type=")) drmType = o.substring(34).trim();
                    else if (o.startsWith("inputstream.adaptive.license_key=")) drmKey = o.substring(33).trim();
                } else if (line.startsWith("#EXTGRP:")) {
                    group = line.substring(8).trim();
                } else if (!line.isEmpty() && !line.startsWith("#")) {
                    String url = line;
                    int bar = url.indexOf('|');
                    if (bar > 0) { // gaya Kodi: url|User-Agent=..&Referer=..
                        for (String kv : url.substring(bar + 1).split("&")) {
                            String[] p = kv.split("=", 2);
                            if (p.length < 2) continue;
                            String k = p[0].toLowerCase();
                            String v = java.net.URLDecoder.decode(p[1], "UTF-8");
                            if (k.equals("user-agent")) ua = v;
                            else if (k.equals("referer") || k.equals("referrer")) ref = v;
                            else if (k.equals("origin")) origin = v;
                        }
                        url = url.substring(0, bar);
                    }
                    if (url.startsWith("http")) {
                        Channel c = new Channel(name == null || name.isEmpty() ? "Saluran " + (out.size() + 1) : name, url, group, logo);
                        c.ua = ua;
                        c.referer = ref;
                        c.origin = origin;
                        c.drmType = drmType;
                        c.drmKey = drmKey;
                        out.add(c);
                    }
                    name = null;
                    group = "";
                    logo = "";
                    ua = ref = origin = drmType = drmKey = null;
                }
            }
        }
        return out;
    }

    /** Semak saluran mana yang hidup (8 serentak). Panggil balik di thread utama bila siap. */
    static void probe(final Runnable done) {
        final List<Channel> list = channels;
        new Thread(new Runnable() {
            @Override
            public void run() {
                java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(8);
                for (final Channel c : list) {
                    ex.execute(new Runnable() {
                        @Override
                        public void run() {
                            c.alive = check(c);
                        }
                    });
                }
                ex.shutdown();
                try {
                    ex.awaitTermination(10, java.util.concurrent.TimeUnit.MINUTES);
                } catch (InterruptedException ignored) {
                }
                if (done != null) main.post(done);
            }
        }, "probe").start();
    }

    private static boolean check(Channel c) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(c.url).openConnection();
            con.setConnectTimeout(7000);
            con.setReadTimeout(7000);
            con.setInstanceFollowRedirects(true);
            con.setRequestProperty("User-Agent", c.ua != null ? c.ua : UA);
            if (c.referer != null) con.setRequestProperty("Referer", c.referer);
            if (c.origin != null) con.setRequestProperty("Origin", c.origin);
            int code = con.getResponseCode();
            if (code < 200 || code >= 400) return false;
            InputStream in = con.getInputStream();
            byte[] b = new byte[512];
            int n = in.read(b);
            return n > 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    static final String UA = "Mozilla/5.0 (Linux; Android 11; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    static void playChannel(int index) {
        BaseActivity t = top();
        Intent i = new Intent(app, LiveTvActivity.class).putExtra("index", index);
        if (t instanceof LiveTvActivity) {
            ((LiveTvActivity) t).onNewIntent(i);
        } else {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(i);
        }
    }

    // ---------- gambar ----------

    // ---------- laporan Live TV (telefon › Lagi › Laporan ralat) ----------

    private static final java.util.concurrent.ExecutorService LOGIO = java.util.concurrent.Executors.newSingleThreadExecutor();

    /** Tambah satu baris (bertarikh) ke laporan Live TV; simpan 40 baris terakhir. */
    static void playLog(String line) {
        if (app == null) return;
        final String l = new java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.ROOT).format(new java.util.Date())
                + "  " + line.replace('\n', ' ');
        LOGIO.execute(new Runnable() {
            @Override
            public void run() {
                File f = new File(app.getFilesDir(), "playlog.txt");
                List<String> lines = new ArrayList<>();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
                    String s;
                    while ((s = r.readLine()) != null) lines.add(s);
                } catch (Exception ignored) {
                }
                lines.add(l);
                while (lines.size() > 40) lines.remove(0);
                try (OutputStream o = new FileOutputStream(f)) {
                    StringBuilder sb = new StringBuilder();
                    for (String s : lines) sb.append(s).append('\n');
                    o.write(sb.toString().getBytes("UTF-8"));
                } catch (Exception ignored) {
                }
            }
        });
    }

    /** Laporan Live TV, terkini dahulu ("" jika tiada). */
    static String playLogText() {
        if (app == null) return "";
        File f = new File(app.getFilesDir(), "playlog.txt");
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            String s;
            while ((s = r.readLine()) != null) lines.add(s);
        } catch (Exception ignored) {
        }
        Collections.reverse(lines);
        StringBuilder sb = new StringBuilder();
        for (String s : lines) sb.append(s).append("\n\n");
        return sb.toString().trim();
    }

    static File imagesDir() {
        File d = new File(app.getFilesDir(), "images");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Gambar terbaru dulu. */
    static List<File> images() {
        File[] fs = imagesDir().listFiles(new java.io.FileFilter() {
            @Override
            public boolean accept(File f) {
                return f.isFile() && !f.getName().endsWith(".part"); // muat naik separuh jalan bukan gambar
            }
        });
        if (fs == null) return new ArrayList<>();
        List<File> l = new ArrayList<>(Arrays.asList(fs));
        Collections.sort(l, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        return l;
    }

    static File videoWallpaperFile() {
        return new File(app.getFilesDir(), "wallpaper.mp4");
    }

    static void showImage(String name, boolean slideshow) {
        BaseActivity t = top();
        Intent i = new Intent(app, ImageViewerActivity.class).putExtra("name", name).putExtra("slideshow", slideshow);
        if (t instanceof ImageViewerActivity) {
            ((ImageViewerActivity) t).onNewIntent(i);
        } else {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(i);
        }
    }

    /** Tukar wallpaper (aurora, nebula, grad:night, grad:dusk, photo:NAMA, video). */
    static void setWallpaper(String spec) {
        Store.setWallpaper(app, spec);
        BaseActivity t = top();
        if (t instanceof MainActivity) ((MainActivity) t).reloadWallpaper();
    }

    static Context ctx() {
        return app;
    }
}
