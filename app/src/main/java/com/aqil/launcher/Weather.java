package com.aqil.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Calendar;
import java.util.Locale;

/** Cuaca semasa dari Open-Meteo (percuma, tanpa kunci). Lokasi: tetapan telefon atau anggaran IP. */
final class Weather {
    static volatile String place = "";
    static volatile int temp = Integer.MIN_VALUE;
    static volatile int code = -1;
    private static long fetchedAt;

    private Weather() {}

    static void refresh(final Context c, final boolean force, final Runnable done) {
        if (!force && System.currentTimeMillis() - fetchedAt < 30 * 60 * 1000 && temp != Integer.MIN_VALUE) {
            if (done != null) done.run();
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String[] p = Store.weatherPlace(c);
                    if (p == null) {
                        JSONObject ip = new JSONObject(get("http://ip-api.com/json/?fields=status,city,regionName,lat,lon"));
                        if ("success".equals(ip.optString("status"))) {
                            String name = ip.optString("city") + ", " + ip.optString("regionName");
                            p = new String[]{name, String.valueOf(ip.optDouble("lat")), String.valueOf(ip.optDouble("lon"))};
                        }
                    }
                    if (p != null) {
                        JSONObject w = new JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=" + p[1] + "&longitude=" + p[2]
                                + "&current=temperature_2m,weather_code"));
                        JSONObject cur = w.getJSONObject("current");
                        temp = (int) Math.round(cur.getDouble("temperature_2m"));
                        code = cur.getInt("weather_code");
                        place = p[0];
                        fetchedAt = System.currentTimeMillis();
                    }
                } catch (Exception ignored) {
                }
                if (done != null) Hub.main.post(done);
            }
        }, "weather").start();
    }

    /** Cari bandar (cth "Kulai") dan simpan sebagai lokasi cuaca. Pulangkan nama atau null. */
    static String setCity(Context c, String city) {
        try {
            JSONObject o = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=1&name="
                    + URLEncoder.encode(city, "UTF-8")));
            JSONArray r = o.optJSONArray("results");
            if (r == null || r.length() == 0) return null;
            JSONObject x = r.getJSONObject(0);
            String name = x.optString("name") + (x.has("admin1") ? ", " + x.optString("admin1") : "");
            Store.setWeatherPlace(c, name, x.getDouble("latitude"), x.getDouble("longitude"));
            refresh(c, true, null);
            return name;
        } catch (Exception e) {
            return null;
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    static String tempText() {
        return temp == Integer.MIN_VALUE ? "--°C" : String.format(Locale.ROOT, "%d°C", temp);
    }

    /** Ikon cuaca dilukis sendiri (matahari/bulan/awan/hujan/ribut). */
    static final class Icon extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Icon(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), u = Math.min(w, h) / 24f;
            int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            boolean night = hour < 7 || hour >= 19;
            int k = code;
            boolean sun = k <= 3 && k >= 0, cloud = k != 0, rain = (k >= 51 && k <= 67) || (k >= 80 && k <= 82), storm = k >= 95;
            if (k < 0) {
                sun = true;
                cloud = true;
            }
            if (sun) {
                float cx = cloud ? 9 * u : 12 * u, cy = cloud ? 9 * u : 12 * u, r = 4.5f * u;
                p.setStyle(Paint.Style.FILL);
                if (night) {
                    p.setColor(0xFFE8E6FF);
                    cv.drawCircle(cx, cy, r + u, p);
                    p.setColor(0xFF1A1F33);
                    cv.drawCircle(cx + 2.4f * u, cy - 1.6f * u, r, p);
                } else {
                    p.setColor(0xFFFFC53D);
                    cv.drawCircle(cx, cy, r, p);
                    p.setStrokeWidth(1.6f * u);
                    p.setStrokeCap(Paint.Cap.ROUND);
                    for (int i = 0; i < 8; i++) {
                        double a = i * Math.PI / 4;
                        cv.drawLine(cx + (float) Math.cos(a) * 6.5f * u, cy + (float) Math.sin(a) * 6.5f * u,
                                cx + (float) Math.cos(a) * 8.6f * u, cy + (float) Math.sin(a) * 8.6f * u, p);
                    }
                }
            }
            if (cloud) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(rain || storm ? 0xFFB8C2D6 : 0xFFF4F7FF);
                float ox = sun ? 2 * u : 0, oy = sun ? 3 * u : 0;
                cv.drawCircle(ox + 9 * u, oy + 13 * u, 4 * u, p);
                cv.drawCircle(ox + 14 * u, oy + 11 * u, 5.2f * u, p);
                cv.drawCircle(ox + 18 * u, oy + 14 * u, 3.4f * u, p);
                cv.drawRect(ox + 9 * u, oy + 13 * u, ox + 18 * u, oy + 17.4f * u, p);
                cv.drawCircle(ox + 9 * u, oy + 15 * u, 2.4f * u, p);
                if (rain) {
                    p.setColor(0xFF64B5FF);
                    p.setStrokeWidth(1.4f * u);
                    for (int i = 0; i < 3; i++) cv.drawLine(ox + (10 + i * 3.5f) * u, oy + 19 * u, ox + (9 + i * 3.5f) * u, oy + 22 * u, p);
                }
                if (storm) {
                    p.setColor(0xFFFFD60A);
                    path.reset();
                    path.moveTo(ox + 14 * u, oy + 17 * u);
                    path.lineTo(ox + 11.5f * u, oy + 21 * u);
                    path.lineTo(ox + 14 * u, oy + 21 * u);
                    path.lineTo(ox + 12.5f * u, oy + 24 * u);
                    path.lineTo(ox + 16.5f * u, oy + 19.5f * u);
                    path.lineTo(ox + 14 * u, oy + 19.5f * u);
                    path.close();
                    cv.drawPath(path, p);
                }
            }
        }
    }
}
