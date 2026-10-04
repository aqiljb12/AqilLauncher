package com.aqil.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.util.ArrayList;
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

/**
 * Server HTTP + WebSocket kecil (tanpa pustaka). Halaman remote untuk telefon, API kawalan,
 * dan WebSocket untuk kekunci (sambungan kekal = respons pantas & stabil).
 */
final class RemoteServer {
    /** Port tetap dulu (supaya alamat tak berubah-ubah), kemudian alternatif. */
    private static final int[] PORTS = {8686, 8687, 8688, 8080, 8888, 9090};
    static volatile int port = PORTS[0];
    private static final int MAX_JSON = 1024 * 1024;
    private static final long MAX_UPLOAD = 300L * 1024 * 1024;

    private final Context ctx;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile ServerSocket server;
    private volatile boolean running;
    private final Map<String, long[]> fails = new HashMap<>();
    private final Map<String, byte[]> iconCache = new HashMap<>();

    RemoteServer(Context c) {
        ctx = c;
    }

    void start() {
        running = true;
        new Thread(() -> {
            while (running && server == null) {
                for (int p : PORTS) {
                    try {
                        ServerSocket s = new ServerSocket();
                        s.setReuseAddress(true);
                        s.bind(new InetSocketAddress(p));
                        server = s;
                        port = p;
                        break;
                    } catch (IOException ignored) {
                    }
                }
                if (server == null) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
            while (running) {
                try {
                    final Socket s = server.accept();
                    s.setTcpNoDelay(true);
                    pool.execute(() -> serve(s));
                } catch (IOException e) {
                    if (!running) break;
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
        String method, path, ip;
        Map<String, String> q = new HashMap<>();
        Map<String, String> h = new HashMap<>();
        InputStream in;
        long len;
        private byte[] body;

        String cookie(String name) {
            String c = h.get("cookie");
            if (c == null) return null;
            for (String part : c.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equals(name)) return kv[1];
            }
            return null;
        }

        byte[] body() throws IOException {
            if (body == null) {
                if (len > MAX_JSON) throw new IOException("terlalu besar");
                body = new byte[(int) Math.max(0, len)];
                int off = 0;
                while (off < body.length) {
                    int k = in.read(body, off, body.length - off);
                    if (k < 0) throw new IOException("putus");
                    off += k;
                }
            }
            return body;
        }

        JSONObject json() {
            try {
                return new JSONObject(new String(body(), "UTF-8"));
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        /** Salin badan permintaan terus ke fail (untuk gambar/video besar, tanpa guna memori). */
        void saveTo(File f) throws IOException {
            if (len > MAX_UPLOAD) throw new IOException("Fail terlalu besar (maks 300MB)");
            File tmp = new File(f.getPath() + ".part");
            try (OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                long left = len;
                while (left > 0) {
                    int k = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (k < 0) throw new IOException("putus");
                    out.write(buf, 0, k);
                    left -= k;
                }
            }
            if (f.exists()) f.delete();
            if (!tmp.renameTo(f)) throw new IOException("gagal simpan");
        }
    }

    private static final class Res {
        int code = 200;
        String type = "application/json; charset=utf-8";
        byte[] body = new byte[0];
        String setCookie, cache;

        static Res json(Object o) {
            Res r = new Res();
            try {
                r.body = o.toString().getBytes("UTF-8");
            } catch (Exception ignored) {
            }
            return r;
        }

        static Res ok() {
            return json(obj("ok", true));
        }

        static Res result(String err) {
            if (err == null) return ok();
            JSONObject o = obj("ok", false);
            put(o, "msg", err);
            return json(o);
        }

        static Res error(int code, String msg) {
            JSONObject o = obj("ok", false);
            put(o, "msg", msg);
            Res r = json(o);
            r.code = code;
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
            InputStream in = new BufferedInputStream(s.getInputStream());
            Req r = readHead(in);
            r.ip = s.getInetAddress().getHostAddress();
            if ("websocket".equalsIgnoreCase(r.h.get("upgrade")) && r.path.equals("/ws")) {
                websocket(s, in, r);
                return;
            }
            Res res;
            try {
                res = route(r);
            } catch (Exception e) {
                res = Res.error(500, String.valueOf(e.getMessage()));
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

    private Req readHead(InputStream in) throws IOException {
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
        r.in = in;
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
        r.len = cl == null ? 0 : Long.parseLong(cl.trim());
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
        String head = "HTTP/1.1 " + r.code + (r.code == 200 ? " OK" : " ERR") + "\r\n"
                + "Content-Type: " + r.type + "\r\n"
                + "Content-Length: " + r.body.length + "\r\n"
                + "Cache-Control: " + (r.cache == null ? "no-store" : r.cache) + "\r\n"
                + (r.setCookie != null ? "Set-Cookie: " + r.setCookie + "\r\n" : "")
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(r.body);
        out.flush();
    }

    // ------------------------------------------------------------------ WebSocket

    private void websocket(Socket s, InputStream in, Req r) throws Exception {
        OutputStream out = s.getOutputStream();
        if (!Store.validToken(ctx, r.cookie("t"))) {
            write(out, Res.error(401, "Perlu PIN"));
            return;
        }
        String key = r.h.get("sec-websocket-key");
        String accept = Base64.encodeToString(MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("UTF-8")), Base64.NO_WRAP);
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes("UTF-8"));
        out.flush();
        s.setSoTimeout(90000); // telefon hantar ping setiap 20s
        while (running) {
            int b0 = in.read(), b1 = in.read();
            if (b0 < 0 || b1 < 0) return;
            int op = b0 & 0x0F;
            long len = b1 & 0x7F;
            if (len == 126) len = (in.read() << 8) | in.read();
            else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
            }
            if (len > 65536) return;
            byte[] mask = new byte[4];
            if ((b1 & 0x80) != 0) for (int i = 0; i < 4; i++) mask[i] = (byte) in.read();
            byte[] data = new byte[(int) len];
            int off = 0;
            while (off < len) {
                int k = in.read(data, off, (int) len - off);
                if (k < 0) return;
                off += k;
            }
            for (int i = 0; i < data.length; i++) data[i] ^= mask[i % 4];
            if (op == 8) {
                wsSend(out, 8, new byte[0]);
                return;
            } else if (op == 9) {
                wsSend(out, 10, data);
            } else if (op == 1) {
                String reply = wsMessage(new String(data, "UTF-8"));
                if (reply != null) wsSend(out, 1, reply.getBytes("UTF-8"));
            }
        }
    }

    private static synchronized void wsSend(OutputStream out, int op, byte[] data) throws IOException {
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write(0x80 | op);
        if (data.length < 126) f.write(data.length);
        else {
            f.write(126);
            f.write(data.length >> 8);
            f.write(data.length & 0xFF);
        }
        f.write(data);
        out.write(f.toByteArray());
        out.flush();
    }

    /** {"id":1,"k":"up"} | {"id":2,"t":"tap","x":..,"y":..} | {"ping":1} */
    private String wsMessage(String msg) {
        try {
            JSONObject j = new JSONObject(msg);
            if (j.has("ping")) return "{\"pong\":1}";
            String err;
            if (j.has("k")) err = RemoteControl.key(ctx, j.getString("k"));
            else if (j.has("t")) err = RemoteControl.touch(j.getString("t"), (float) j.optDouble("x"), (float) j.optDouble("y"),
                    (float) j.optDouble("x2"), (float) j.optDouble("y2"));
            else return null;
            JSONObject o = new JSONObject();
            o.put("id", j.optInt("id"));
            o.put("ok", err == null);
            if (err != null) o.put("msg", err);
            return o.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ laluan

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
            case "/api/launch": {
                String pkg = r.json().optString("pkg");
                String err = RemoteControl.launch(ctx, pkg);
                if (err == null) Store.recordLaunch(ctx, pkg);
                return Res.result(err);
            }
            case "/api/url": return Res.result(RemoteControl.openUrl(ctx, r.json().optString("url")));
            case "/api/text": {
                JSONObject j = r.json();
                return Res.result(RemoteControl.text(j.optString("text"), j.optBoolean("append", false)));
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
                Hub.main.post(() -> Hub.playChannel(i));
                return Res.ok();
            }
            case "/api/playlist": return playlist(r.json().optString("url").trim());
            case "/api/log": return Res.json(obj("log", App.lastCrash()));
            case "/api/probe": {
                if (Store.xtream(ctx) != null) return Res.result("Semakan dimatikan untuk akaun IPTV (elak had sambungan).");
                Hub.probe(null);
                return Res.ok();
            }
            case "/api/xtream": {
                JSONObject j = r.json();
                if (j.optBoolean("logout")) {
                    Xtream.logout(ctx);
                } else {
                    String err = Xtream.login(ctx, j.optString("server"), j.optString("user"), j.optString("pass"));
                    if (err != null) return Res.result(err);
                }
                final CountDownLatch l = new CountDownLatch(1);
                final int[] n = {0};
                Hub.loadChannels(true, (count, e) -> {
                    n[0] = count;
                    l.countDown();
                });
                l.await(60, TimeUnit.SECONDS);
                Hub.main.post(() -> {
                    BaseActivity t = Hub.top();
                    if (t instanceof MainActivity) ((MainActivity) t).refreshCurrent();
                });
                JSONObject o = obj("ok", true);
                put(o, "count", n[0]);
                put(o, "info", Xtream.summary());
                return Res.json(o);
            }
            case "/api/upload": return upload(r);
            case "/api/upload/video": return uploadVideo(r);
            case "/api/images": return images();
            case "/img": return image(r);
            case "/api/image/show": {
                JSONObject j = r.json();
                final String n = safe(j.optString("name"));
                final boolean slide = j.optBoolean("slideshow", false);
                if (n == null || !new File(Hub.imagesDir(), n).exists()) return Res.error(404, "Tiada gambar");
                Hub.main.post(() -> Hub.showImage(n, slide));
                return Res.ok();
            }
            case "/api/image/wall": {
                final String n = safe(r.json().optString("name"));
                if (n == null || !new File(Hub.imagesDir(), n).exists()) return Res.error(404, "Tiada gambar");
                Hub.main.post(() -> Hub.setWallpaper("photo:" + n));
                return Res.ok();
            }
            case "/api/image/delete": {
                String n = safe(r.json().optString("name"));
                if (n != null) new File(Hub.imagesDir(), n).delete();
                return Res.ok();
            }
            case "/api/wallpaper": {
                final String spec = r.json().optString("spec", "aurora");
                if (!spec.matches("aurora|nebula|video|grad:night|grad:dusk")) return Res.error(400, "Tak sah");
                if (spec.equals("video") && !Hub.videoWallpaperFile().exists()) return Res.error(404, "Belum ada video");
                Hub.main.post(() -> Hub.setWallpaper(spec));
                return Res.ok();
            }
            case "/api/weather": {
                String name = Weather.setCity(ctx, r.json().optString("city"));
                if (name == null) return Res.error(404, "Bandar tak dijumpai");
                JSONObject o = obj("ok", true);
                put(o, "place", name);
                return Res.json(o);
            }
            default:
                return Res.error(404, "Tak jumpa");
        }
    }

    private Res pair(Req r) {
        long[] f;
        synchronized (fails) {
            f = fails.get(r.ip);
            if (f == null) fails.put(r.ip, f = new long[2]);
            if (System.currentTimeMillis() < f[1]) return Res.error(429, "Terlalu banyak cubaan. Tunggu 30 saat.");
        }
        if (r.json().optString("pin").equals(Store.pin(ctx))) {
            synchronized (fails) {
                f[0] = 0;
            }
            Res res = Res.ok();
            res.setCookie = "t=" + Store.addToken(ctx) + "; Path=/; Max-Age=31536000; HttpOnly; SameSite=Lax";
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
        String[] xt = Store.xtream(ctx);
        if (xt != null) {
            put(o, "xtServer", xt[0]);
            put(o, "xtUser", xt[1]);
            put(o, "xtInfo", Xtream.summary());
        }
        put(o, "wall", Store.wallpaper(ctx));
        put(o, "video", Hub.videoWallpaperFile().exists());
        put(o, "place", Weather.place);
        put(o, "temp", Weather.tempText());
        BaseActivity t = Hub.top();
        put(o, "screen", t == null ? "Apl lain" : t instanceof MainActivity ? "Launcher" : t instanceof LiveTvActivity ? "Live TV" : "Galeri");
        return Res.json(o);
    }

    private Res apps() throws Exception {
        List<Apps.A> list = Apps.all;
        if (list.isEmpty()) {
            final CountDownLatch l = new CountDownLatch(1);
            Apps.load(ctx, l::countDown);
            l.await(10, TimeUnit.SECONDS);
            list = Apps.all;
        }
        JSONArray a = new JSONArray();
        for (Apps.A x : list) a.put(new JSONObject().put("pkg", x.pkg).put("label", x.label));
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
            Bitmap b = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, 128, 128);
            d.draw(new Canvas(b));
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

    private Res channels() throws Exception {
        JSONArray a = new JSONArray();
        List<Channel> l = Hub.channels;
        for (int i = 0; i < l.size(); i++) {
            Channel c = l.get(i);
            a.put(new JSONArray().put(i).put(c.name).put(c.group).put(c.logo).put(c.alive == null ? 0 : c.alive ? 1 : -1));
        }
        return Res.json(a);
    }

    private Res playlist(String url) throws Exception {
        if (!url.startsWith("http")) return Res.error(400, "URL mesti bermula dengan http");
        Store.setPlaylist(ctx, url);
        final CountDownLatch latch = new CountDownLatch(1);
        final int[] count = {0};
        final String[] err = {null};
        Hub.loadChannels(true, (n, e) -> {
            count[0] = n;
            err[0] = e;
            latch.countDown();
        });
        latch.await(40, TimeUnit.SECONDS);
        JSONObject o = new JSONObject();
        put(o, "ok", err[0] == null);
        put(o, "count", count[0]);
        put(o, "msg", err[0] == null ? "" : err[0]);
        return Res.json(o);
    }

    // ------------------------------------------------------------------ gambar & video

    private static String safe(String n) {
        if (n == null || n.isEmpty() || n.contains("/") || n.contains("\\") || n.contains("..")) return null;
        return n;
    }

    private Res upload(Req r) throws Exception {
        if (r.len <= 0) return Res.error(400, "Kosong");
        String name = System.currentTimeMillis() + "_" + (int) (Math.random() * 1000) + ".jpg";
        File f = new File(Hub.imagesDir(), name);
        r.saveTo(f);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) {
            f.delete();
            return Res.error(415, "Bukan gambar yang sah");
        }
        if ("1".equals(r.q.get("show"))) Hub.main.post(() -> Hub.showImage(name, false));
        if ("1".equals(r.q.get("wall"))) Hub.main.post(() -> Hub.setWallpaper("photo:" + name));
        return Res.json(obj("name", name));
    }

    private Res uploadVideo(Req r) throws Exception {
        if (r.len <= 0) return Res.error(400, "Kosong");
        File f = Hub.videoWallpaperFile();
        r.saveTo(f);
        Hub.main.post(() -> {
            Store.setWallpaper(ctx, "aurora"); // paksa muat semula walaupun spec sama
            Hub.setWallpaper("video");
        });
        return Res.ok();
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
            Bitmap b = ImageViewerActivity.decode(f, 240, 240);
            if (b == null) return Res.error(415, "Rosak");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, 75, bo);
            res.body = bo.toByteArray();
        } else {
            res.body = readAll(new FileInputStream(f));
        }
        return res;
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
