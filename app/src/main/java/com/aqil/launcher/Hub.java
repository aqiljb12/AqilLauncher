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
        c.setRequestProperty("User-Agent", "AqilLauncher/2.0");
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
            String line, name = null, group = "";
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#EXTINF")) {
                    int comma = line.lastIndexOf(',');
                    name = comma >= 0 ? line.substring(comma + 1).trim() : "Saluran";
                    group = "";
                    Matcher m = ATTR.matcher(line);
                    while (m.find()) if (m.group(1).equals("group-title")) group = m.group(2);
                } else if (!line.isEmpty() && !line.startsWith("#")) {
                    if (line.startsWith("http")) {
                        out.add(new Channel(name == null || name.isEmpty() ? "Saluran " + (out.size() + 1) : name, line, group));
                    }
                    name = null;
                    group = "";
                }
            }
        }
        return out;
    }

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

    static File wallpaperFile() {
        return new File(app.getFilesDir(), "wallpaper.jpg");
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

    static void wallpaperChanged() {
        BaseActivity t = top();
        if (t instanceof MainActivity) ((MainActivity) t).reloadWallpaper();
    }

    static Context ctx() {
        return app;
    }
}
