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

    /** Muat turun (atau guna cache) senarai M3U di thread lain; panggil balik di thread utama. */
    static void loadChannels(final boolean force, final Done done) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                File cache = new File(app.getFilesDir(), "playlist.m3u");
                if (force || !cache.exists() || channels.isEmpty()) {
                    try {
                        download(Store.playlist(app), cache);
                    } catch (Exception e) {
                        err = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                if (channels.isEmpty() || force) {
                    try {
                        if (cache.exists()) channels = parse(cache);
                    } catch (Exception e) {
                        err = e.toString();
                    }
                }
                final int n = channels.size();
                final String fe = n == 0 && err == null ? "Senarai kosong" : (n == 0 ? err : null);
                if (done != null) main.post(new Runnable() {
                    @Override
                    public void run() {
                        done.run(n, fe);
                    }
                });
            }
        }, "playlist").start();
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
            con.setRequestProperty("Range", "bytes=0-2047");
            int code = con.getResponseCode();
            if (code / 100 != 2) return false;
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

    static File imagesDir() {
        File d = new File(app.getFilesDir(), "images");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Gambar terbaru dulu. */
    static List<File> images() {
        File[] fs = imagesDir().listFiles();
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
