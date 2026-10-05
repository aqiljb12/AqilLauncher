package com.aqil.launcher;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.FrameMetrics;
import android.view.Window;

/**
 * Prestasi automatik – tiada suis, apl sesuaikan diri dengan TV:
 *  - Tahap kesan visual TINGGI / SEDERHANA / RINGAN dikesan dari RAM, CPU & resolusi UI. Pada Android 7+
 *    bingkai sebenar dipantau (FrameMetrics); jika kerap terlepas, tahap diturunkan sendiri dan diingati.
 *  - Tunneling video (Live TV) dicuba hanya jika cip TV menyokongnya; jika gagal ia dimatikan sendiri.
 * Hasil pengesanan ditetapkan semula bila versi apl berubah.
 */
final class Perf {
    static final int LOW = 0, MID = 1, HIGH = 2;
    private static volatile int tier = -1;
    private static Boolean tunnelHw;
    private static HandlerThread thread;

    private Perf() {}

    static int tier(Context c) {
        if (tier < 0) {
            Context a = c.getApplicationContext();
            Store.perfVersion(a, versionCode(a));
            int d = detect(a), cap = Store.perfCap(a);
            tier = cap >= 0 ? Math.min(d, cap) : d;
        }
        return tier;
    }

    /** Animasi berterusan: wallpaper live bergerak, parallax, warna ambien. */
    static boolean motion(Context c) {
        return Store.fx(c) && tier(c) >= MID;
    }

    /** Kad condong masuk 3D bila difokus. */
    static boolean tilt(Context c) {
        return Store.fx(c) && tier(c) >= MID;
    }

    /** Kesan paling mahal: sinaran melintas kad, Ken Burns banner, bayang pada semua kad. */
    static boolean rich(Context c) {
        return Store.fx(c) && tier(c) >= HIGH;
    }

    /** Bayang kad (bila difokus). Tahap RINGAN: tiada bayang langsung. */
    static boolean shadows(Context c) {
        return tier(c) >= MID;
    }

    /** Gambar dinyahkod 16-bit (separuh memori) bila TV bukan tahap TINGGI. */
    static boolean lite(Context c) {
        return tier(c) < HIGH;
    }

    static String tierName(Context c) {
        int t = tier(c);
        return t == HIGH ? "Tinggi" : t == MID ? "Sederhana" : "Ringan";
    }

    private static int detect(Context c) {
        long totalMb = 2048;
        boolean lowRam = false;
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            totalMb = mi.totalMem >> 20;
            lowRam = Build.VERSION.SDK_INT >= 19 && am.isLowRamDevice();
        } catch (Throwable ignored) {
        }
        int cores = Runtime.getRuntime().availableProcessors();
        boolean bigUi = (long) S.w * S.h > 1920L * 1080 * 3 / 2; // UI 4K = 4x piksel untuk dilukis setiap bingkai
        if (lowRam || totalMb < 1300 || cores <= 2) return LOW;
        // Android 6 ke bawah tiada pemantau bingkai → jangan terlalu yakin
        if (totalMb < 1700 || (bigUi && totalMb < 3000) || Build.VERSION.SDK_INT < 24) return MID;
        return HIGH;
    }

    @SuppressWarnings("deprecation")
    private static int versionCode(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Kesan semula dari awal (Tetapan › Prestasi automatik). */
    static void reset(Context c) {
        Store.setPerfCap(c, -1);
        Store.setTunnel(c, 0);
        Store.setTunnelOks(c, 0);
        tier = -1;
    }

    private static void downgrade(Activity a) {
        if (tier <= LOW || a.isFinishing()) return;
        tier--;
        Store.setPerfCap(a, tier);
        if (a instanceof MainActivity) ((MainActivity) a).perfChanged();
    }

    // ---------------------------------------------------------------- pemantau bingkai (Android 7+)

    /** Mula pantau bingkai tetingkap ini; pulangkan token untuk unwatch() (null jika tidak perlu). */
    static Object watch(Activity a) {
        if (Build.VERSION.SDK_INT < 24 || tier(a) <= LOW) return null;
        try {
            if (thread == null) {
                thread = new HandlerThread("perf");
                thread.start();
            }
            Watch w = new Watch(a);
            a.getWindow().addOnFrameMetricsAvailableListener(w, new Handler(thread.getLooper()));
            return w;
        } catch (Throwable t) {
            return null;
        }
    }

    static void unwatch(Activity a, Object token) {
        if (token == null || Build.VERSION.SDK_INT < 24) return;
        try {
            a.getWindow().removeOnFrameMetricsAvailableListener((Window.OnFrameMetricsAvailableListener) token);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Kira bingkai yang mengambil masa ≥ ~2 kitaran skrin (pasti terlepas). Jika ≥20% daripada 240 bingkai
     * dalam dua tetingkap berturut-turut, turunkan tahap satu langkah. Hanya bingkai yang benar-benar dilukis
     * dikira, jadi skrin yang diam tidak menjejaskan keputusan.
     */
    @TargetApi(24)
    private static final class Watch implements Window.OnFrameMetricsAvailableListener {
        private final Activity a;
        private long graceUntil, budgetNs;
        private int frames, slow, bad;

        Watch(Activity a) {
            this.a = a;
            graceUntil = SystemClock.uptimeMillis() + 6000; // abaikan bingkai semasa skrin baru dibina
        }

        @Override
        public void onFrameMetricsAvailable(Window w, FrameMetrics m, int dropped) {
            if (tier <= LOW || SystemClock.uptimeMillis() < graceUntil) return;
            if (Build.VERSION.SDK_INT >= 26 && m.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1) return;
            if (frames == 0) {
                float hz = 60f;
                try {
                    hz = a.getWindowManager().getDefaultDisplay().getRefreshRate();
                } catch (Throwable ignored) {
                }
                budgetNs = (long) (1e9 / Math.max(24f, Math.min(hz, 144f)));
            }
            frames++;
            if (m.getMetric(FrameMetrics.TOTAL_DURATION) > budgetNs * 19 / 10) slow++;
            if (frames < 240) return;
            bad = slow * 5 >= frames ? bad + 1 : 0;
            frames = 0;
            slow = 0;
            if (bad < 2) return;
            bad = 0;
            graceUntil = SystemClock.uptimeMillis() + 5000;
            Hub.main.post(new Runnable() {
                @Override
                public void run() {
                    downgrade(a);
                }
            });
        }
    }

    // ---------------------------------------------------------------- tunneling video

    /** Cuba tunneling untuk Live TV? Hanya jika cip menyokong & belum terbukti gagal pada TV ini. */
    static boolean tunnel(Context c) {
        tier(c); // pastikan hasil lama dari versi lain sudah ditetapkan semula
        return Build.VERSION.SDK_INT >= 23 && Store.tunnel(c) >= 0 && tunnelHw();
    }

    /** Satu saluran main stabil dalam mod tunneling; selepas 3 kali TV ini dikira serasi. */
    static void tunnelOk(Context c) {
        if (Store.tunnel(c) != 0) return;
        int n = Store.tunnelOks(c) + 1;
        Store.setTunnelOks(c, n);
        if (n >= 3) Store.setTunnel(c, 1);
    }

    /** Gagal semasa percubaan → matikan terus pada TV ini. Jika sudah disahkan sebelum ini, hanya sesi ini. */
    static void tunnelFailed(Context c) {
        if (Store.tunnel(c) != 1) Store.setTunnel(c, -1);
    }

    static String tunnelName(Context c) {
        if (Build.VERSION.SDK_INT < 23 || !tunnelHw()) return "tidak disokong TV ini";
        int s = Store.tunnel(c);
        return s > 0 ? "aktif" : s < 0 ? "dimatikan (tidak serasi)" : "sedang diuji";
    }

    private static synchronized boolean tunnelHw() {
        if (tunnelHw == null) {
            boolean ok = false;
            try {
                for (MediaCodecInfo i : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                    if (i.isEncoder()) continue;
                    for (String t : i.getSupportedTypes()) {
                        if (t.equalsIgnoreCase("video/avc") && i.getCapabilitiesForType(t)
                                .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback)) ok = true;
                    }
                }
            } catch (Throwable ignored) {
            }
            tunnelHw = ok;
        }
        return tunnelHw;
    }
}
