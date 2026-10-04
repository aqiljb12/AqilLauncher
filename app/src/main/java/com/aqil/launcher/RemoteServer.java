package com.aqil.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Server HTTP kecil (tanpa pustaka) yang hidangkan halaman remote untuk telefon + API kawalan. */
final class RemoteServer {
    static volatile int port = 8080;
    private static final int MAX_BODY = 48 * 1024 * 1024;

    private final Context ctx;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile ServerSocket server;
    private volatile boolean running;
    private final Map<String, long[]> fails = new HashMap<>(); // ip -> {count, lockedUntil}
    private final Map<String, byte[]> iconCache = new HashMap<>();

    RemoteServer(Context c) {
        ctx = c;
    }

    void start() {
        running = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int p = 8080; p < 8100 && running; p++) {
                    try {
                        server = new ServerSocket(p);
                        port = p;
                        break;
                    } catch (IOException ignored) {
                    }
                }
                if (server == null) return;
                while (running) {
                    try {
                        final Socket s = server.accept();
                        pool.execute(new Runnable() {
                            @Override
                            public void run() {
                                serve(s);
                            }
                        });
                    } catch (IOException e) {
                        if (!running) break;
                    }
                }
            }
        }, "remote-accept").start();
    }

    void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    // ------------------------------------------------------------------ HTTP

    private static final class Req {
        String method, path;
        Map<String, String> q = new HashMap<>();
        Map<String, String> h = new HashMap<>();
        byte[] body = new byte[0];
        String ip;

        String cookie(String name) {
            String c = h.get("cookie");
            if (c == null) return null;
            for (String part : c.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equals(name)) return kv[1];
            }
            return null;
        }

        JSONObject json() {
            try {
                return new JSONObject(new String(body, "UTF-8"));
            } catch (Exception e) {
                return new JSONObject();
            }
        }
    }

    private static final class Res {
        int code = 200;
        String type = "application/json; charset=utf-8";
        byte[] body = new byte[0];
        String setCookie;
        String cache;

        static Res json(JSONObject o) {
            Res r = new Res();
            try {
                r.body = o.toString().getBytes("UTF-8");
            } catch (Exception ignored) {
            }
            return r;
        }

        static Res json(JSONArray a) {
            Res r = new Res();
            try {
                r.body = a.toString().getBytes("UTF-8");
            } catch (Exception ignored) {
            }
            return r;
        }

        static Res ok() {
            return json(obj("ok", true));
        }

        /** err == null => OK, selain itu {ok:false,msg:err}. */
        static Res result(String err) {
            if (err == null) return ok();
            JSONObject o = obj("ok", false);
            put(o, "msg", err);
            return json(o);
        }

        static Res error(int code, String msg) {
            Res r = json(obj("ok", false));
            r.code = code;
            try {
                r.body = new JSONObject().put("ok", false).put("msg", msg).toString().getBytes("UTF-8");
            } catch (Exception ignored) {
            }
            return r;
        }
    }

    private static JSONObject obj(String k, Object v) {
        JSONObject o = new JSONObject();
        put(o, k, v);
        return o;
    }

    private static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (Exception ignored) {
        }
    }

    private void serve(Socket s) {
        try {
            s.setSoTimeout(30000);
            InputStream in = new java.io.BufferedInputStream(s.getInputStream());
            Req r = readRequest(in);
            r.ip = s.getInetAddress().getHostAddress();
            Res res;
            try {
                res = route(r);
            } catch (Exception e) {
                res = Res.error(500, String.valueOf(e));
            }
            write(s.getOutputStream(), res);
        } catch (Exception ignored) {
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private Req readRequest(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int a = 0, b = 0, c = 0, d;
        while ((d = in.read()) != -1) {
            head.write(d);
            if (a == '\r' && b == '\n' && c == '\r' && d == '\n') break;
            a = b;
            b = c;
            c = d;
            if (head.size() > 32768) throw new IOException("header terlalu besar");
        }
        String[] lines = head.toString("UTF-8").split("\r\n");
        String[] first = lines[0].split(" ");
        Req r = new Req();
        r.method = first[0];
        String target = first.length > 1 ? first[1] : "/";
        int qi = target.indexOf('?');
        r.path = qi < 0 ? target : target.substring(0, qi);
        if (qi >= 0) {
            for (String kv : target.substring(qi + 1).split("&")) {
                String[] p = kv.split("=", 2);
                r.q.put(dec(p[0]), p.length > 1 ? dec(p[1]) : "");
            }
        }
        for (int i = 1; i < lines.length; i++) {
            int ci = lines[i].indexOf(':');
            if (ci > 0) r.h.put(lines[i].substring(0, ci).trim().toLowerCase(Locale.ROOT), lines[i].substring(ci + 1).trim());
        }
        String cl = r.h.get("content-length");
        if (cl != null) {
            int n = Integer.parseInt(cl);
            if (n > MAX_BODY) throw new IOException("terlalu besar");
            byte[] body = new byte[n];
            int off = 0;
            while (off < n) {
                int k = in.read(body, off, n - off);
                if (k < 0) throw new IOException("putus");
                off += k;
            }
            r.body = body;
        }
        return r;
    }

    private static String dec(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private void write(OutputStream out, Res r) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(r.code).append(r.code == 200 ? " OK" : " ERR").append("\r\n");
        sb.append("Content-Type: ").append(r.type).append("\r\n");
        sb.append("Content-Length: ").append(r.body.length).append("\r\n");
        sb.append("Cache-Control: ").append(r.cache == null ? "no-store" : r.cache).append("\r\n");
        if (r.setCookie != null) sb.append("Set-Cookie: ").append(r.setCookie).append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes("UTF-8"));
        out.write(r.body);
        out.flush();
    }

    // ------------------------------------------------------------------ routes

    private Res route(Req r) throws Exception {
        String p = r.path;
        if (r.method.equals("GET") && (p.equals("/") || p.equals("/index.html"))) {
            Res res = new Res();
            res.type = "text/html; charset=utf-8";
            res.body = readAll(ctx.getAssets().open("remote.html"));
            return res;
        }
        if (p.equals("/api/pair")) return pair(r);
        if (!Store.validToken(ctx, r.cookie("t"))) return Res.error(401, "Perlu PIN");

        switch (p) {
            case "/api/state": return state();
            case "/api/apps": return apps();
            case "/api/icon": return icon(r.q.get("pkg"));
            case "/api/key": return Res.result(RemoteControl.key(ctx, r.json().optString("k")));
            case "/api/launch": return Res.result(RemoteControl.launch(ctx, r.json().optString("pkg")));
            case "/api/url": return Res.result(RemoteControl.openUrl(ctx, r.json().optString("url")));
            case "/api/text": {
                JSONObject j = r.json();
                String t = j.optString("text");
                String err = RemoteControl.text(t, j.optBoolean("append", false));
                return Res.result(err);
            }
            case "/api/touch": {
                JSONObject j = r.json();
                return Res.result(RemoteControl.touch(j.optString("t"), (float) j.optDouble("x"), (float) j.optDouble("y"),
                        (float) j.optDouble("x2"), (float) j.optDouble("y2")));
            }
            case "/api/channels": return channels();
            case "/api/play": {
                final int i = r.json().optInt("i", -1);
                if (i < 0 || i >= Hub.channels.size()) return Res.error(400, "Saluran tak sah");
                Hub.main.post(new Runnable() {
                    @Override
                    public void run() {
                        Hub.playChannel(i);
                    }
                });
                return Res.ok();
            }
            case "/api/playlist": return playlist(r.json().optString("url").trim());
            case "/api/upload": return upload(r);
            case "/api/images": return images();
            case "/img": return image(r);
            case "/api/image/show": {
                final String n = safe(r.json().optString("name"));
                final boolean slide = r.json().optBoolean("slideshow", false);
                if (n == null || !new File(Hub.imagesDir(), n).exists()) return Res.error(404, "Tiada gambar");
                Hub.main.post(new Runnable() {
                    @Override
                    public void run() {
                        Hub.showImage(n, slide);
                    }
                });
                return Res.ok();
            }
            case "/api/image/wall": return wall(safe(r.json().optString("name")));
            case "/api/image/delete": {
                String n = safe(r.json().optString("name"));
                if (n != null) new File(Hub.imagesDir(), n).delete();
                return Res.ok();
            }
            case "/api/wall/clear":
                Hub.wallpaperFile().delete();
                Hub.main.post(new Runnable() {
                    @Override
                    public void run() {
                        Hub.wallpaperChanged();
                    }
                });
                return Res.ok();
            default:
                return Res.error(404, "Tak jumpa");
        }
    }

    private Res pair(Req r) {
        long[] f;
        synchronized (fails) {
            f = fails.get(r.ip);
            if (f == null) fails.put(r.ip, f = new long[2]);
            if (System.currentTimeMillis() < f[1]) return Res.error(429, "Terlalu banyak cubaan. Tunggu sebentar.");
        }
        if (r.json().optString("pin").equals(Store.pin(ctx))) {
            synchronized (fails) {
                f[0] = 0;
            }
            Res res = Res.ok();
            res.setCookie = "t=" + Store.addToken(ctx) + "; Path=/; Max-Age=31536000; HttpOnly";
            return res;
        }
        synchronized (fails) {
            if (++f[0] >= 5) {
                f[0] = 0;
                f[1] = System.currentTimeMillis() + 30000;
            }
        }
        return Res.error(403, "PIN salah");
    }

    private Res state() {
        JSONObject o = new JSONObject();
        put(o, "ok", true);
        put(o, "a11y", RemoteAccessibilityService.instance != null);
        put(o, "channels", Hub.channels.size());
        put(o, "playing", Hub.playing);
        put(o, "playlist", Store.playlist(ctx));
        put(o, "sdk", android.os.Build.VERSION.SDK_INT);
        BaseActivity t = Hub.top();
        put(o, "screen", t == null ? "luar" : t.getClass().getSimpleName());
        return Res.json(o);
    }

    private List<ResolveInfo> launchables() {
        PackageManager pm = ctx.getPackageManager();
        Set<String> seen = new HashSet<>();
        List<ResolveInfo> out = new ArrayList<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(ctx.getPackageName()) || !seen.add(pkg)) continue;
                out.add(ri);
            }
        }
        final PackageManager fpm = pm;
        Collections.sort(out, new Comparator<ResolveInfo>() {
            @Override
            public int compare(ResolveInfo a, ResolveInfo b) {
                return a.loadLabel(fpm).toString().compareToIgnoreCase(b.loadLabel(fpm).toString());
            }
        });
        return out;
    }

    private Res apps() throws Exception {
        PackageManager pm = ctx.getPackageManager();
        JSONArray a = new JSONArray();
        for (ResolveInfo ri : launchables()) {
            a.put(new JSONObject().put("pkg", ri.activityInfo.packageName).put("label", ri.loadLabel(pm).toString()));
        }
        return Res.json(a);
    }

    private Res icon(String pkg) throws Exception {
        if (pkg == null) return Res.error(400, "pkg?");
        byte[] png;
        synchronized (iconCache) {
            png = iconCache.get(pkg);
        }
        if (png == null) {
            Drawable d = ctx.getPackageManager().getApplicationIcon(pkg);
            Bitmap b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, 96, 96);
            d.draw(c);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, bo);
            b.recycle();
            png = bo.toByteArray();
            synchronized (iconCache) {
                iconCache.put(pkg, png);
            }
        }
        Res r = new Res();
        r.type = "image/png";
        r.body = png;
        r.cache = "max-age=3600";
        return r;
    }

    private Res channels() {
        JSONArray a = new JSONArray();
        List<Channel> l = Hub.channels;
        for (int i = 0; i < l.size(); i++) {
            a.put(new JSONArray().put(i).put(l.get(i).name).put(l.get(i).group));
        }
        return Res.json(a);
    }

    private Res playlist(String url) throws Exception {
        if (!url.startsWith("http")) return Res.error(400, "URL mesti bermula dengan http");
        Store.setPlaylist(ctx, url);
        final CountDownLatch latch = new CountDownLatch(1);
        final int[] count = {0};
        final String[] err = {null};
        Hub.loadChannels(true, new Hub.Done() {
            @Override
            public void run(int n, String e) {
                count[0] = n;
                err[0] = e;
                latch.countDown();
            }
        });
        latch.await(40, TimeUnit.SECONDS);
        JSONObject o = new JSONObject();
        put(o, "ok", err[0] == null);
        put(o, "count", count[0]);
        put(o, "msg", err[0] == null ? "" : err[0]);
        return Res.json(o);
    }

    // ------------------------------------------------------------------ gambar

    /** Nama fail selamat (tiada path traversal). */
    private static String safe(String n) {
        if (n == null || n.isEmpty() || n.contains("/") || n.contains("\\") || n.contains("..")) return null;
        return n;
    }

    private Res upload(Req r) throws Exception {
        if (r.body.length == 0) return Res.error(400, "Kosong");
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(r.body, 0, r.body.length, o);
        if (o.outWidth <= 0) return Res.error(415, "Bukan gambar yang sah");
        String name = System.currentTimeMillis() + "_" + (int) (Math.random() * 1000) + ".jpg";
        File f = new File(Hub.imagesDir(), name);
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(r.body);
        }
        if ("1".equals(r.q.get("show"))) {
            final String n = name;
            final boolean slide = false;
            Hub.main.post(new Runnable() {
                @Override
                public void run() {
                    Hub.showImage(n, slide);
                }
            });
        }
        return Res.json(obj("name", name));
    }

    private Res images() throws Exception {
        JSONArray a = new JSONArray();
        for (File f : Hub.images()) a.put(new JSONObject().put("name", f.getName()).put("t", f.lastModified()));
        return Res.json(a);
    }

    private Res image(Req r) throws Exception {
        String n = safe(r.q.get("n"));
        File f = n == null ? null : new File(Hub.imagesDir(), n);
        if (f == null || !f.exists()) return Res.error(404, "Tiada gambar");
        Res res = new Res();
        res.type = "image/jpeg";
        res.cache = "max-age=86400";
        if ("1".equals(r.q.get("thumb"))) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 4;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
            if (b == null) return Res.error(415, "Rosak");
            Bitmap s = Bitmap.createScaledBitmap(b, 240, Math.max(1, b.getHeight() * 240 / b.getWidth()), true);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            s.compress(Bitmap.CompressFormat.JPEG, 75, bo);
            res.body = bo.toByteArray();
        } else {
            res.body = readAll(new FileInputStream(f));
        }
        return res;
    }

    private Res wall(String n) throws Exception {
        if (n == null) return Res.error(400, "Nama tak sah");
        File src = new File(Hub.imagesDir(), n);
        if (!src.exists()) return Res.error(404, "Tiada gambar");
        Bitmap b = ImageViewerActivity.decode(src, 1920, 1080);
        if (b == null) return Res.error(415, "Rosak");
        try (OutputStream out = new FileOutputStream(Hub.wallpaperFile())) {
            b.compress(Bitmap.CompressFormat.JPEG, 90, out);
        }
        Hub.main.post(new Runnable() {
            @Override
            public void run() {
                Hub.wallpaperChanged();
            }
        });
        return Res.ok();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = i.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }
}
