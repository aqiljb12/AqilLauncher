package com.aqil.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Pemuat gambar URL (logo saluran, poster filem):
 *  - cache memori (1/8 heap) + cache storan (cacheDir/img, had 80MB, buang yang paling lama tidak dipakai)
 *    → buka kali kedua terus keluar tanpa Internet, dan tidak berebut lebar jalur dengan siaran
 *  - giliran LIFO: gambar yang baru diminta (yang sedang di skrin) dimuat dulu bila skrol laju
 *  - permintaan URL sama digabung; permintaan untuk paparan yang sudah bertukar gambar dilangkau
 *  - dinyahkod tepat ke saiz paparan (16-bit pada TV yang bukan tahap Tinggi)
 */
final class Img {
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(memBytes()) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };
    private static final Map<String, List<ImageView>> WAIT = new HashMap<>();
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS,
            new LinkedBlockingDeque<Runnable>() {
                @Override
                public boolean offer(Runnable r) {
                    return offerFirst(r); // LIFO
                }
            });
    private static final long DISK_MAX = 80L * 1024 * 1024;
    private static File dir;
    private static int writes;

    static {
        IO.allowCoreThreadTimeOut(true);
    }

    private Img() {}

    private static int memBytes() {
        long mb = Runtime.getRuntime().maxMemory() / (1024 * 1024) / 8;
        return (int) Math.max(8, Math.min(48, mb)) * 1024 * 1024;
    }

    static void load(final ImageView v, final String url, final int maxPx) {
        v.setTag(url);
        if (url == null || url.isEmpty()) {
            v.setImageDrawable(null);
            return;
        }
        final String key = maxPx + "|" + url;
        Bitmap hit = CACHE.get(key);
        if (hit != null) {
            v.setImageBitmap(hit);
            return;
        }
        v.setImageDrawable(null);
        synchronized (WAIT) {
            List<ImageView> l = WAIT.get(key);
            if (l != null) { // sudah dalam perjalanan: tumpang sahaja
                if (!l.contains(v)) l.add(v);
                return;
            }
            l = new ArrayList<>();
            l.add(v);
            WAIT.put(key, l);
        }
        IO.execute(new Runnable() {
            @Override
            public void run() {
                final boolean skipped = !wanted(key, url);
                final Bitmap b = skipped ? null : fetch(url, maxPx);
                if (b != null) CACHE.put(key, b);
                Hub.main.post(new Runnable() {
                    @Override
                    public void run() {
                        List<ImageView> l;
                        synchronized (WAIT) {
                            l = WAIT.remove(key);
                        }
                        if (l == null) return;
                        for (ImageView iv : l) {
                            if (!url.equals(iv.getTag())) continue;
                            if (b != null) iv.setImageBitmap(b);
                            else if (skipped) load(iv, url, maxPx); // diminta semula selepas dilangkau
                        }
                    }
                });
            }
        });
    }

    /** Masih ada paparan yang mahu gambar ini? (paparan yang sudah ditukar ke gambar lain tidak dikira) */
    private static boolean wanted(String key, String url) {
        synchronized (WAIT) {
            List<ImageView> l = WAIT.get(key);
            if (l == null) return false;
            for (ImageView iv : l) if (url.equals(iv.getTag())) return true;
            return false;
        }
    }

    private static Bitmap fetch(String url, int maxPx) {
        try {
            boolean web = url.startsWith("http://") || url.startsWith("https://");
            File f = web ? new File(dir(), md5(url)) : null;
            byte[] data = null;
            if (f != null && f.length() > 0) {
                data = readFile(f);
                //noinspection ResultOfMethodCallIgnored
                f.setLastModified(System.currentTimeMillis()); // untuk buang-yang-lama (LRU)
            }
            if (data == null) {
                data = download(url);
                if (data == null) return null;
                if (f != null) save(f, data);
            }
            return decode(data, maxPx);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private static byte[] download(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", Hub.UA);
            if (c.getResponseCode() / 100 != 2) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                    if (bo.size() > 4 * 1024 * 1024) return null; // bukan logo/poster
                }
                return bo.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private static Bitmap decode(byte[] data, int maxPx) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int s = 1;
        while (o.outWidth / (s * 2) >= maxPx) s *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = s;
        // RGB_565 hanya dipakai untuk gambar legap (poster); logo lut sinar kekal 32-bit secara automatik
        if (Perf.lite(Hub.app)) o.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (b == null) return null;
        if (b.getWidth() > maxPx * 5 / 4) { // kecilkan tepat ke saiz paparan → jimat memori & lebih banyak muat dalam cache
            Bitmap sc = Bitmap.createScaledBitmap(b, maxPx, Math.max(1, b.getHeight() * maxPx / b.getWidth()), true);
            if (sc != b) b.recycle();
            b = sc;
        }
        return b;
    }

    // ---------------------------------------------------------------- cache storan

    private static synchronized File dir() {
        if (dir == null) {
            dir = new File(Hub.app.getCacheDir(), "img");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    private static String md5(String s) throws Exception {
        byte[] d = java.security.MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder(32);
        for (byte x : d) sb.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        return sb.toString();
    }

    private static byte[] readFile(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            return off == b.length ? b : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void save(File f, byte[] data) {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(f);
        boolean trim;
        synchronized (Img.class) {
            trim = ++writes % 40 == 1; // semak saiz sekali-sekala sahaja
        }
        if (trim) trim();
    }

    private static synchronized void trim() {
        File[] fs = dir().listFiles();
        if (fs == null) return;
        long total = 0;
        for (File f : fs) total += f.length();
        if (total <= DISK_MAX) return;
        Arrays.sort(fs, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        for (File f : fs) {
            if (total <= DISK_MAX * 4 / 5) break;
            total -= f.length();
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}
