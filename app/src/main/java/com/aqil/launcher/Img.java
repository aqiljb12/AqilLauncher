package com.aqil.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Pemuat gambar URL (logo saluran) yang ringkas: cache memori + 3 thread. */
final class Img {
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };
    private static final ExecutorService IO = Executors.newFixedThreadPool(3);

    private Img() {}

    static void load(final ImageView v, final String url, final int maxPx) {
        v.setTag(url);
        if (url == null || url.isEmpty()) {
            v.setImageDrawable(null);
            return;
        }
        Bitmap hit = CACHE.get(url);
        if (hit != null) {
            v.setImageBitmap(hit);
            return;
        }
        v.setImageDrawable(null);
        IO.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap b = fetch(url, maxPx);
                if (b != null) CACHE.put(url, b);
                Hub.main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (b != null && url.equals(v.getTag())) v.setImageBitmap(b);
                    }
                });
            }
        });
    }

    private static Bitmap fetch(String url, int maxPx) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", Hub.UA);
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && bo.size() < 4 * 1024 * 1024) bo.write(buf, 0, n);
                byte[] data = bo.toByteArray();
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, o);
                int s = 1;
                while (o.outWidth / (s * 2) >= maxPx) s *= 2;
                o.inJustDecodeBounds = false;
                o.inSampleSize = s;
                return BitmapFactory.decodeByteArray(data, 0, data.length, o);
            }
        } catch (Exception | OutOfMemoryError e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
