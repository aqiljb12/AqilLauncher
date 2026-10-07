package com.aqil.launcher;

import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.view.WindowManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.video.VideoFrameMetadataListener;

import java.util.List;

/**
 * Live TV guna ExoPlayer (Media3): HLS, DASH, MPEG-TS, MP4, pengepala UA/Referer dari M3U, ClearKey/Widevine.
 * Penimbal besar + cuba semula automatik + langkau saluran rosak = tontonan lancar.
 * Atas/bawah (atau CH+/-) tukar saluran, OK/kiri buka senarai, nombor untuk lompat ke saluran.
 */
@OptIn(markerClass = UnstableApi.class)
public class LiveTvActivity extends BaseActivity {
    /** Had langkau automatik berturut-turut (saluran gagal satu demi satu). */
    private static final int MAX_AUTO_SKIPS = 15;
    /** Cuba semula saluran yang sama sebelum dianggap gagal. */
    private static final int MAX_RETRIES = 2;
    /** Had "sambung semula ke hujung siaran" (BEHIND_LIVE_WINDOW / tersekat) bagi setiap saluran. */
    private static final int MAX_RESYNCS = 3;
    /** Main lancar selama ini (ms) baru saluran dikira "benar-benar berjaya" dan pembilang direset. */
    private static final long STABLE_MS = 5000;

    /** Semua panggilan tertangguh (zap, cuba semula, langkau, nombor, watchdog) melalui satu Handler supaya mudah dibatalkan. */
    private final Handler h = new Handler(Looper.getMainLooper());
    /** true hanya antara onStart() dan onStop(): tiada tindakan main balik semasa apl lain di depan. */
    private boolean started;
    /** Saluran dipilih sebelum senarai siap / semasa aktiviti terhenti: mainkan bila onStart(). */
    private boolean needInitial;
    private ExoPlayer player;
    private SurfaceView surface;
    private FrameLayout videoBox, panel, info;
    private ListView list;
    private TextView infoNum, infoName, infoGroup, status, digits, resLabel, netLabel, epgNow, epgNext;
    private View epgTrack, epgBar;
    private FrameLayout.LayoutParams infoLp;
    private ImageView infoLogo;
    private ProgressBar spinner;
    private ChAdapter adapter;
    private int index = -1, pending = -1, retries, skips, resyncs, lastStep = 1;
    private boolean triedHls;
    private long bufferingSince;
    private String digitBuf = "";

    private final Runnable hideInfo = new Runnable() {
        @Override
        public void run() {
            info.animate().alpha(0f).translationY(S.px(30)).setDuration(400).start();
            diag.animate().alpha(0f).setDuration(400).start();
        }
    };
    private final Runnable zap = new Runnable() {
        @Override
        public void run() {
            if (started && pending >= 0) start(pending);
        }
    };
    private final Runnable digitGo = new Runnable() {
        @Override
        public void run() {
            int n = digitBuf.isEmpty() ? 0 : Integer.parseInt(digitBuf);
            digitBuf = "";
            digits.setVisibility(View.GONE);
            if (started && n >= 1 && n <= Hub.channels.size()) select(n - 1, 0, false);
        }
    };
    /** Dipanggil STABLE_MS selepas READY: jika masih main, saluran ini benar-benar berjaya. */
    private final Runnable stable = new Runnable() {
        @Override
        public void run() {
            if (started && player != null && player.getPlaybackState() == Player.STATE_READY) {
                skips = 0;
                retries = 0;
                resyncs = 0;
                if (tunnelOn && firstFrame) Perf.tunnelOk(LiveTvActivity.this);
            }
        }
    };
    // ---- anti-gegaran: kesan fps siaran dari cap masa bingkai, kemudian padankan kadar segar skrin
    private final long[] frameTs = new long[40];
    private volatile int frameCount;
    private volatile boolean fpsDone;
    private int savedModeId = -1;

    // ---- tunneling automatik: dicuba jika cip TV menyokong; jika tiada gambar / gambar beku / ralat dekoder,
    //      pemain dibina semula dalam mod biasa dan TV ini diingati sebagai tidak serasi.
    private boolean tunnelOn, tunnelOff, firstFrame;
    private int playTicks, frozenTicks, lastFrames = -1, probeFrames = -1;
    private long probeAt;

    private final Runnable tunnelCheck = new Runnable() {
        @Override
        public void run() {
            if (!started || player == null || !tunnelOn) return;
            if (player.getPlaybackState() == Player.STATE_READY && player.isPlaying() && player.getVideoFormat() != null) {
                playTicks++;
                int f = frames();
                if (!firstFrame) {
                    if (playTicks >= 3) { // ~6 saat main tanpa sebarang gambar
                        tunnelFallback();
                        return;
                    }
                } else if (f >= 0) {
                    frozenTicks = f == lastFrames ? frozenTicks + 1 : 0;
                    lastFrames = f;
                    if (frozenTicks >= 4) { // gambar beku ~8 saat walaupun siaran berjalan
                        tunnelFallback();
                        return;
                    }
                }
            } else {
                frozenTicks = 0;
            }
            h.postDelayed(this, 2000);
        }
    };

    /**
     * Kesan fps dari bilangan bingkai dekoder dalam ~3 saat – sandaran bila cap masa bingkai tidak tersedia
     * (mod tunneling: bingkai dipapar terus oleh cip, bukan oleh apl).
     */
    private final Runnable fpsProbe = new Runnable() {
        @Override
        public void run() {
            if (!started || player == null || fpsDone || player.getPlaybackState() != Player.STATE_READY || !player.isPlaying()) {
                probeFrames = -1;
                return;
            }
            int f = frames();
            if (f < 0) return;
            long now = SystemClock.elapsedRealtime();
            if (probeFrames < 0 || f < probeFrames) {
                probeFrames = f;
                probeAt = now;
                h.postDelayed(this, 3000);
                return;
            }
            long dt = now - probeAt;
            int df = f - probeFrames;
            probeFrames = -1;
            if (dt < 2000 || df < 20) return;
            fpsDone = true;
            onFps(snapFps(df * 1000f / dt / Math.max(0.5f, player.getPlaybackParameters().speed)));
        }
    };

    private int frames() {
        DecoderCounters dc = player == null ? null : player.getVideoDecoderCounters();
        if (dc == null) return -1;
        dc.ensureUpdated();
        return dc.renderedOutputBufferCount + dc.skippedOutputBufferCount + dc.droppedBufferCount;
    }

    private static float snapFps(float f) {
        float best = -1, err = 1f;
        for (float std : new float[]{23.976f, 24f, 25f, 29.97f, 30f, 48f, 50f, 59.94f, 60f}) {
            float e = Math.abs(f - std) / std;
            if (e < err) {
                err = e;
                best = std;
            }
        }
        return err <= 0.06f ? best : -1;
    }

    private void tunnelFallback() {
        Perf.tunnelFailed(this);
        tunnelOff = true; // sesi ini terus guna mod biasa
        rebuildPlayer();
    }

    /** Bina semula pemain & sambung saluran semasa (dekoder baharu). */
    private void rebuildPlayer() {
        h.removeCallbacks(tunnelCheck);
        h.removeCallbacks(fpsProbe);
        h.removeCallbacks(stable);
        h.removeCallbacks(cushionTick);
        h.removeCallbacks(swRetry);
        if (player != null) {
            player.release();
            player = null;
        }
        frameCount = 0;
        createPlayer();
        if (started && index >= 0 && index < Hub.channels.size())
            play(Hub.channels.get(index), triedHls ? MimeTypes.APPLICATION_M3U8 : null);
    }

    // ---- statistik strim (dipapar bersama info saluran) & laporan sesi untuk telefon (Lagi › Laporan ralat)
    private TextView diag;
    private String decoderName = "";
    private float streamFps = -1;
    private int stalls, droppedBase;
    private long stallMs, stallSince, sessionStart;
    private boolean wasReady, progressive, swRetried;
    /** Dekoder perisian yang sudah dicuba semula dan masih perisian (cth format tidak disokong cip) – jangan ulang. */
    private final java.util.Set<String> swKnown = new java.util.HashSet<>();

    /**
     * Dekoder perisian (CPU) dipilih walaupun TV ada dekoder perkakasan – biasanya kerana dekoder perkakasan sedang
     * dipegang sekejap (pratonton / wallpaper video). Bina semula pemain sekali untuk dapatkan dekoder perkakasan.
     */
    private final Runnable swRetry = new Runnable() {
        @Override
        public void run() {
            if (started && player != null && Perf.isSoftware(decoderName)) rebuildPlayer();
        }
    };

    private void onDecoder(String name) {
        decoderName = name == null ? "" : name;
        androidx.media3.common.Format f = player == null ? null : player.getVideoFormat();
        String mime = f == null ? null : f.sampleMimeType;
        if (!Perf.isSoftware(decoderName) || mime == null) return;
        String key = mime + "/" + f.height;
        if (swRetried) {
            swKnown.add(key); // sudah dicuba: format ini memang perlu dekoder perisian pada TV ini
            Hub.playLog("Dekoder perisian digunakan (" + decoderName + ", " + mime + " " + f.width + "x" + f.height + ")");
            return;
        }
        if (swKnown.contains(key) || !Perf.hwDecoder(mime)) return;
        swRetried = true;
        h.removeCallbacks(swRetry);
        h.postDelayed(swRetry, 1200);
    }

    private void onFps(float fps) {
        streamFps = fps;
        matchRefreshRate(fps);
    }

    private int dropped() {
        DecoderCounters dc = player == null ? null : player.getVideoDecoderCounters();
        if (dc == null) return 0;
        dc.ensureUpdated();
        return dc.droppedBufferCount;
    }

    private long stallTotal() {
        return stallMs + (stallSince > 0 ? SystemClock.elapsedRealtime() - stallSince : 0);
    }

    private String diagText() {
        if (player == null) return "";
        java.util.Locale L = java.util.Locale.ROOT;
        androidx.media3.common.Format f = player.getVideoFormat();
        float hz = 0;
        try {
            hz = getWindowManager().getDefaultDisplay().getRefreshRate();
        } catch (RuntimeException ignored) {
        }
        StringBuilder sb = new StringBuilder("STATISTIK STRIM\n");
        sb.append("Video  ").append(f == null || f.width <= 0 ? "–" : f.width + "×" + f.height)
                .append(streamFps > 0 ? "  •  " + String.format(L, streamFps % 1 == 0 ? "%.0f" : "%.2f", streamFps) + " fps" : "")
                .append("  →  skrin ").append(Math.round(hz)).append("Hz\n");
        sb.append("Dekoder  ").append(decoderName.isEmpty() ? "–" : (Perf.isSoftware(decoderName) ? "PERISIAN (berat)" : "perkakasan"))
                .append(tunnelOn ? " + tunneling" : "").append('\n');
        if (!decoderName.isEmpty()) sb.append("   ").append(decoderName).append('\n');
        long bps = Streams.bitrateEstimate();
        sb.append("Penimbal  ").append(player.getTotalBufferedDuration() / 1000).append("s  •  muat turun ")
                .append(bps > 0 ? String.format(L, "%.1f", bps / 1e6) + " Mbps" : "–").append('\n');
        sb.append("Tersekat  ").append(stalls).append("×");
        if (stalls > 0) sb.append(" (").append(stallTotal() / 1000).append("s)");
        sb.append("  •  bingkai hilang ").append(Math.max(0, dropped() - droppedBase));
        if (cushionTarget > 0) sb.append("\nPenimbal tambahan  ").append(cushionTarget / 1000).append("s (server/Internet goyang)");
        return sb.toString();
    }

    /** Ringkasan sesi saluran ke laporan telefon – supaya punca "lag" boleh dikenal pasti dari data sebenar. */
    private void logSession() {
        if (sessionStart == 0 || index < 0 || index >= Hub.channels.size()) return;
        long mins = (SystemClock.elapsedRealtime() - sessionStart) / 60000;
        sessionStart = 0;
        if (mins < 1 && stalls == 0) return;
        androidx.media3.common.Format f = player == null ? null : player.getVideoFormat();
        long bps = Streams.bitrateEstimate();
        Hub.playLog(Hub.channels.get(index).name + " • " + mins + " min • "
                + (f == null || f.width <= 0 ? "?" : f.width + "x" + f.height)
                + (streamFps > 0 ? " " + Math.round(streamFps) + "fps" : "")
                + " → skrin " + Math.round(getWindowManager().getDefaultDisplay().getRefreshRate()) + "Hz • "
                + (decoderName.isEmpty() ? "dekoder ?" : (Perf.isSoftware(decoderName) ? "PERISIAN " : "HW ") + decoderName)
                + (tunnelOn ? " +tunneling" : "") + " • tersekat " + stalls + "x (" + stallTotal() / 1000 + "s) • hilang "
                + Math.max(0, dropped() - droppedBase) + " bingkai • " + (bps > 0 ? String.format(java.util.Locale.ROOT, "%.1f", bps / 1e6) : "?")
                + " Mbps" + (cushionTarget > 0 ? " • penimbal tambahan " + cushionTarget / 1000 + "s" : ""));
    }

    // ---- penimbal adaptif: strim TS langsung yang tersekat berulang kali → jeda sebentar untuk kumpul penimbal lebih
    //      (server menghantar pada kelajuan masa nyata, jadi setiap saat jeda = 1 saat tambahan tahan goyang)
    private int cushionTarget;
    private long cushionSince;
    private boolean cushioning;

    private final Runnable cushionTick = new Runnable() {
        @Override
        public void run() {
            if (!started || player == null || !cushioning) return;
            long buf = player.getTotalBufferedDuration();
            long waited = SystemClock.elapsedRealtime() - cushionSince;
            if (buf >= cushionTarget || waited > cushionTarget + 4000 || player.getPlaybackState() != Player.STATE_READY) {
                endCushion();
                return;
            }
            status.setText("Server/Internet tidak stabil – menimbal lebih supaya tidak tersekat lagi…  "
                    + buf / 1000 + " / " + cushionTarget / 1000 + "s");
            h.postDelayed(this, 500);
        }
    };

    private void maybeCushion() {
        if (!progressive || !Store.smooth(this) || stalls < 2 || player == null) return;
        cushionTarget = (int) Math.min(24000, 6000L * stalls); // tersekat ke-2 → 12s, ke-3 → 18s, seterusnya 24s
        if (player.getTotalBufferedDuration() >= cushionTarget) return;
        cushioning = true;
        cushionSince = SystemClock.elapsedRealtime();
        player.setPlayWhenReady(false); // pemuatan diteruskan semasa dijeda → penimbal bertambah
        spinner.setVisibility(View.VISIBLE);
        h.removeCallbacks(cushionTick);
        h.post(cushionTick);
    }

    private final Runnable cushionCheck = new Runnable() {
        @Override
        public void run() {
            if (started && !cushioning) maybeCushion();
        }
    };

    private void endCushion() {
        cushioning = false;
        h.removeCallbacks(cushionTick);
        status.setText("");
        spinner.setVisibility(View.GONE);
        if (player != null) player.setPlayWhenReady(true);
    }

    private final Runnable statsTick = new Runnable() {
        @Override
        public void run() {
            if (!started || player == null) return;
            long buf = player.getTotalBufferedDuration() / 1000;
            long bps = Streams.bitrateEstimate();
            netLabel.setText("Penimbal " + buf + "s" + (bps > 0 ? "  •  " + String.format(java.util.Locale.ROOT, "%.1f", bps / 1e6) + " Mbps" : ""));
            diag.setText(diagText());
            if (info.getAlpha() > 0.01f) h.postDelayed(this, 1000);
        }
    };

    private Channel epgFor;
    private final Runnable epgFetch = new Runnable() {
        @Override
        public void run() {
            final Channel ch = epgFor;
            if (!started || ch == null) return;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final List<Xtream.Prog> l = Xtream.epg(LiveTvActivity.this, ch, 3);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!isDestroyed() && epgFor == ch) showEpg(l);
                        }
                    });
                }
            }, "epg").start();
        }
    };
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!started || player == null) return; // jangan sentuh main balik bila apl lain di depan
            if (player.getPlaybackState() == Player.STATE_BUFFERING && bufferingSince > 0
                    && System.currentTimeMillis() - bufferingSince > 15000) {
                bufferingSince = System.currentTimeMillis();
                if (resyncs < MAX_RESYNCS) {
                    // tersekat terlalu lama: sambung semula ke hujung siaran langsung (terhad)
                    resyncs++;
                    player.seekToDefaultPosition();
                    player.prepare();
                } else {
                    status.setText("Siaran tersekat. Tekan atas/bawah untuk saluran lain.");
                }
            }
            h.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        videoBox = new FrameLayout(this);
        surface = new SurfaceView(this);
        videoBox.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addView(videoBox, new FrameLayout.LayoutParams(-1, -1));

        spinner = new ProgressBar(this);
        root.addView(spinner, new FrameLayout.LayoutParams(S.px(90), S.px(90), Gravity.CENTER));
        status = Ui.text(this, "", 28, Ui.WHITE, Ui.MEDIUM);
        status.setSingleLine(false);
        status.setGravity(Gravity.CENTER);
        status.setShadowLayer(8, 0, 2, 0xFF000000);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(S.px(1200), -2, Gravity.CENTER);
        slp.topMargin = S.px(130);
        root.addView(status, slp);

        // ---- maklumat saluran (bawah kiri)
        info = new FrameLayout(this);
        info.setBackground(Ui.glass(S.px(30)));
        infoLogo = new ImageView(this);
        infoLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        info.addView(infoLogo, Ui.at(26, 22, 170, 96));
        infoNum = Ui.text(this, "", 30, 0xFF64B5FF, Ui.BOLD);
        info.addView(infoNum, Ui.at(224, 24, -2, -2));
        TextView liveBadge = Ui.text(this, " LANGSUNG ", 20, Ui.WHITE, Ui.BOLD);
        liveBadge.setBackground(Ui.solid(Ui.RED, S.px(8)));
        liveBadge.setPadding(S.px(8), S.px(4), S.px(8), S.px(4));
        info.addView(liveBadge, Ui.at(330, 26, -2, -2));
        // resolusi sebenar siaran (cth "FHD 1080p") supaya mudah tahu sama ada siaran itu sendiri kabur
        resLabel = Ui.text(this, "", 20, Ui.WHITE, Ui.BOLD);
        resLabel.setBackground(Ui.solid(0xCC2E8BFF, S.px(8)));
        resLabel.setPadding(S.px(8), S.px(4), S.px(8), S.px(4));
        resLabel.setVisibility(View.GONE);
        info.addView(resLabel, Ui.at(480, 26, -2, -2));
        // kesihatan strim: saat ditimbal + anggaran kelajuan (bantu kenal pasti punca tersekat)
        netLabel = Ui.text(this, "", 20, Ui.DIM, Ui.MEDIUM);
        info.addView(netLabel, Ui.at(640, 30, 230, -2));
        infoName = Ui.text(this, "", 40, Ui.WHITE, Ui.BOLD);
        info.addView(infoName, Ui.at(224, 64, 620, -2));
        infoGroup = Ui.text(this, "", 24, Ui.DIM, Ui.MEDIUM);
        info.addView(infoGroup, Ui.at(224, 116, 620, -2));
        // ---- jadual: Sekarang (dengan bar kemajuan) & Seterusnya
        epgNow = Ui.text(this, "", 24, Ui.WHITE, Ui.MEDIUM);
        info.addView(epgNow, Ui.at(26, 168, 828, -2));
        epgTrack = new View(this);
        epgTrack.setBackground(Ui.solid(0x33FFFFFF, S.px(3)));
        info.addView(epgTrack, Ui.at(26, 206, 828, 6));
        epgBar = new View(this);
        epgBar.setBackground(Ui.solid(0xFF2E8BFF, S.px(3)));
        info.addView(epgBar, Ui.at(26, 206, 0, 6));
        epgNext = Ui.text(this, "", 22, Ui.DIM, Ui.MEDIUM);
        info.addView(epgNext, Ui.at(26, 224, 828, -2));
        showEpg(null);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(S.px(880), S.px(164), Gravity.START | Gravity.BOTTOM);
        infoLp = ilp;
        ilp.leftMargin = S.px(70);
        ilp.bottomMargin = S.px(60);
        info.setAlpha(0f);
        root.addView(info, ilp);

        // ---- statistik strim (atas kanan, bersama info saluran): bantu kenal pasti punca tersekat / patah-patah
        diag = Ui.text(this, "", 20, Ui.WHITE, Ui.MEDIUM);
        diag.setSingleLine(false);
        diag.setLineSpacing(S.px(5), 1f);
        diag.setBackground(Ui.glass(S.px(24)));
        diag.setPadding(S.px(26), S.px(18), S.px(26), S.px(18));
        diag.setAlpha(0f);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(S.px(660), -2, Gravity.TOP | Gravity.END);
        glp.topMargin = glp.rightMargin = S.px(60);
        root.addView(diag, glp);

        digits = Ui.text(this, "", 80, Ui.WHITE, Ui.BOLD);
        digits.setBackground(Ui.glass(S.px(24)));
        digits.setPadding(S.px(30), S.px(10), S.px(30), S.px(10));
        digits.setVisibility(View.GONE);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        dlp.topMargin = dlp.rightMargin = S.px(60);
        root.addView(digits, dlp);

        // ---- senarai saluran (kiri)
        panel = new FrameLayout(this);
        panel.setBackground(Ui.grad(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, 0, 0xF0080A12, 0xD0080A12, 0x00080A12));
        panel.setVisibility(View.GONE);
        TextView ph = Ui.text(this, "Saluran", 40, Ui.WHITE, Ui.BOLD);
        panel.addView(ph, Ui.at(60, 50, -2, -2));
        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(S.px(8));
        list.setVerticalScrollBarEnabled(false);
        list.setSelector(Ui.selected(S.px(18)));
        list.setDrawSelectorOnTop(false);
        list.setCacheColorHint(0);
        adapter = new ChAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                hidePanel();
                select(pos, 0, false);
            }
        });
        panel.addView(list, Ui.at(40, 120, 640, 920));
        root.addView(panel, new FrameLayout.LayoutParams(S.px(820), -1, Gravity.START));
        setContentView(root);

        createPlayer();
        status.setText("Memuatkan senarai saluran…");
        Hub.loadChannels(false, new Hub.Done() {
            @Override
            public void run(int count, String error) {
                if (isFinishing() || isDestroyed()) return;
                adapter.notifyDataSetChanged();
                if (count == 0) {
                    spinner.setVisibility(View.GONE);
                    status.setText("Tiada saluran.\n" + (error == null ? "" : error)
                            + "\nTetapkan URL senarai M3U dari telefon (Remote › Lagi).");
                    return;
                }
                if (started) handle(getIntent(), true);
                else needInitial = true;
            }
        });
    }

    private void createPlayer() {
        tunnelOn = !tunnelOff && Perf.tunnel(this);
        player = Streams.player(this, false, tunnelOn);
        player.setVideoSurfaceView(surface);
        player.addAnalyticsListener(new AnalyticsListener() {
            @Override
            public void onVideoDecoderInitialized(AnalyticsListener.EventTime t, String name, long initializedMs, long durationMs) {
                onDecoder(name);
            }
        });
        player.setVideoFrameMetadataListener(new VideoFrameMetadataListener() {
            @Override
            public void onVideoFrameAboutToBeRendered(long presentationTimeUs, long releaseTimeNs,
                                                      androidx.media3.common.Format format, android.media.MediaFormat mediaFormat) {
                // dipanggil di thread main balik: kumpul 40 cap masa pertama setiap saluran
                if (fpsDone) return;
                int n = frameCount;
                if (n < frameTs.length) {
                    frameTs[n] = presentationTimeUs;
                    frameCount = n + 1;
                }
                if (n + 1 == frameTs.length) {
                    fpsDone = true;
                    final float fps = estimateFps();
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            onFps(fps);
                        }
                    });
                }
            }
        });
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                long now = SystemClock.elapsedRealtime();
                if (state == Player.STATE_BUFFERING && wasReady) { // sedang main lalu tersekat
                    stalls++;
                    stallSince = now;
                } else if (state != Player.STATE_BUFFERING && stallSince > 0) {
                    stallMs += now - stallSince;
                    stallSince = 0;
                    if (state == Player.STATE_READY) h.post(cushionCheck);
                }
                wasReady = state == Player.STATE_READY;
                if (state == Player.STATE_BUFFERING) {
                    if (bufferingSince == 0) bufferingSince = System.currentTimeMillis();
                    spinner.setVisibility(View.VISIBLE);
                } else {
                    bufferingSince = 0;
                    spinner.setVisibility(View.GONE);
                }
                if (state == Player.STATE_READY) {
                    status.setText("");
                    probeFrames = -1;
                    h.removeCallbacks(fpsProbe);
                    h.postDelayed(fpsProbe, 1500);
                    if (tunnelOn) {
                        h.removeCallbacks(tunnelCheck);
                        h.postDelayed(tunnelCheck, 2000);
                    }
                    if (index >= 0 && index < Hub.channels.size()) Hub.channels.get(index).alive = true;
                    // Pembilang gagal direset hanya selepas saluran main lancar STABLE_MS, supaya siaran yang
                    // "READY sekejap lalu mati" tidak boleh menyebabkan kitaran langkau/cuba semula tanpa henti.
                    h.removeCallbacks(stable);
                    h.postDelayed(stable, STABLE_MS);
                } else {
                    h.removeCallbacks(stable);
                }
            }

            @Override
            public void onVideoSizeChanged(VideoSize vs) {
                fit(vs);
                int hgt = vs.height;
                if (hgt <= 0) {
                    resLabel.setVisibility(View.GONE);
                } else {
                    resLabel.setText(hgt >= 2000 ? " 4K " : hgt >= 1000 ? " FHD " + hgt + "p " : hgt >= 700 ? " HD " + hgt + "p " : " SD " + hgt + "p ");
                    resLabel.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onRenderedFirstFrame() {
                firstFrame = true;
            }

            @Override
            public void onPlayerError(PlaybackException e) {
                onError(e);
            }
        });
    }

    @Override
    public void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handle(i, false);
    }

    private void handle(Intent i, boolean first) {
        List<Channel> chs = Hub.channels;
        if (chs.isEmpty()) return;
        int idx = i.getIntExtra("index", -1);
        if (idx < 0 && (first || index < 0)) idx = Math.min(Store.lastChannel(this), chs.size() - 1);
        if (idx < 0) return;
        idx = Math.min(idx, chs.size() - 1);
        if (!started) {
            // onNewIntent datang sebelum onStart(): simpan sahaja, onStart() akan mainkan
            pending = idx;
            skips = 0;
            return;
        }
        select(idx, 0, false);
    }

    /**
     * Pilih saluran: papar maklumat serta-merta, mula main selepas delayMs (zapping laju tanpa sangkut).
     * auto=false (pilihan pengguna) mereset had langkau automatik; auto=true tidak.
     */
    private void select(int i, int delayMs, boolean auto) {
        List<Channel> chs = Hub.channels;
        if (i < 0 || i >= chs.size()) return;
        if (!auto) skips = 0;
        pending = i;
        showInfo(i);
        h.removeCallbacks(zap);
        if (delayMs <= 0) start(i);
        else h.postDelayed(zap, delayMs);
    }

    private void showInfo(int i) {
        Channel c = Hub.channels.get(i);
        infoNum.setText(String.valueOf(i + 1));
        infoName.setText(c.name);
        infoGroup.setText(c.group);
        // EPG diambil hanya bila pilihan berhenti 700ms (zap laju tidak mencipta banyak thread rangkaian)
        h.removeCallbacks(epgFetch);
        epgFor = c; // hasil EPG lama untuk saluran lain akan diabaikan
        showEpg(null);
        if (c.xtId != 0) h.postDelayed(epgFetch, 700);
        Img.load(infoLogo, c.logo, S.px(170));
        info.animate().cancel();
        info.setAlpha(1f);
        info.setTranslationY(0);
        diag.animate().cancel();
        diag.setText(diagText());
        diag.setAlpha(1f);
        h.removeCallbacks(hideInfo);
        h.postDelayed(hideInfo, 4500);
        h.removeCallbacks(statsTick);
        h.post(statsTick);
    }

    /** Median jarak antara bingkai → fps (abaikan lompatan/cap masa pelik). */
    private float estimateFps() {
        int n = frameTs.length;
        long[] d = new long[n - 1];
        int k = 0;
        for (int i = 1; i < n; i++) {
            long dt = frameTs[i] - frameTs[i - 1];
            if (dt > 4000 && dt < 100000) d[k++] = dt; // 10–250 fps sahaja
        }
        if (k < 10) return -1;
        java.util.Arrays.sort(d, 0, k);
        return 1e6f / d[k / 2];
    }

    /**
     * Siaran Malaysia biasanya 25/50 fps tetapi kebanyakan TV berjalan pada 60Hz → gerakan bergegar (nampak
     * "lag"). Jika skrin bukan gandaan fps siaran, minta mod paparan yang sepadan (cth 50Hz) dengan resolusi sama.
     * Dipulihkan bila keluar dari Live TV.
     */
    private void matchRefreshRate(float fps) {
        if (!started || !Store.matchFps(this) || Build.VERSION.SDK_INT < 23 || fps < 10 || fps > 125) return;
        try {
            Display d = getWindowManager().getDefaultDisplay();
            Display.Mode cur = d.getMode();
            if (isMultiple(cur.getRefreshRate(), fps)) return; // sudah lancar
            Display.Mode best = null;
            float bestScore = Float.MAX_VALUE;
            for (Display.Mode m : d.getSupportedModes()) {
                if (m.getPhysicalWidth() != cur.getPhysicalWidth() || m.getPhysicalHeight() != cur.getPhysicalHeight()) continue;
                float r = m.getRefreshRate();
                if (!isMultiple(r, fps)) continue;
                float err = Math.abs(r - Math.round(r / fps) * fps);
                // utamakan ≥48Hz, kemudian padanan paling tepat (24 utk 24fps), kemudian yang terendah (50 dari 100)
                float score = (r < 47 ? 1000 : 0) + err * 100 + r * 0.01f;
                if (score < bestScore) {
                    bestScore = score;
                    best = m;
                }
            }
            if (best == null) return;
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            if (savedModeId < 0) savedModeId = lp.preferredDisplayModeId;
            lp.preferredDisplayModeId = best.getModeId();
            getWindow().setAttributes(lp);
        } catch (RuntimeException ignored) {
        }
    }

    private static boolean isMultiple(float refresh, float fps) {
        float mult = Math.round(refresh / fps);
        return mult >= 1 && Math.abs(refresh - mult * fps) <= 0.6f;
    }

    private void restoreRefreshRate() {
        if (savedModeId < 0 || Build.VERSION.SDK_INT < 23) return;
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.preferredDisplayModeId = savedModeId;
            getWindow().setAttributes(lp);
        } catch (RuntimeException ignored) {
        }
        savedModeId = -1;
    }

    /** Papar jadual (null/kosong = sembunyi & kecilkan kotak info). */
    private void showEpg(List<Xtream.Prog> l) {
        boolean has = l != null && !l.isEmpty();
        int vis = has ? View.VISIBLE : View.GONE;
        epgNow.setVisibility(vis);
        epgNext.setVisibility(View.GONE);
        epgTrack.setVisibility(View.GONE);
        epgBar.setVisibility(View.GONE);
        if (infoLp != null) {
            infoLp.height = S.px(has ? 270 : 164);
            info.setLayoutParams(infoLp);
        }
        if (!has) return;
        Xtream.Prog now = l.get(0);
        long t = System.currentTimeMillis();
        boolean airing = now.start > 0 && now.end > now.start && t >= now.start;
        epgNow.setText((airing ? "Sekarang  " : "Akan datang  ") + (now.start > 0 ? now.hhmm(now.start) + "–" + now.hhmm(now.end) + "   " : "") + now.title);
        if (airing) {
            float f = U.clamp((t - now.start) / (float) (now.end - now.start), 0f, 1f);
            FrameLayout.LayoutParams bp = (FrameLayout.LayoutParams) epgBar.getLayoutParams();
            bp.width = Math.max(S.px(6), Math.round(S.px(828) * f));
            epgBar.setLayoutParams(bp);
            epgTrack.setVisibility(View.VISIBLE);
            epgBar.setVisibility(View.VISIBLE);
        }
        if (l.size() > 1) {
            Xtream.Prog n = l.get(1);
            epgNext.setText("Seterusnya  " + (n.start > 0 ? n.hhmm(n.start) + "   " : "") + n.title);
            epgNext.setVisibility(View.VISIBLE);
        }
    }

    private void start(int i) {
        if (i < 0 || i >= Hub.channels.size()) return;
        logSession();
        sessionStart = SystemClock.elapsedRealtime();
        stalls = 0;
        stallMs = 0;
        streamFps = -1;
        cushionTarget = 0;
        swRetried = false;
        resLabel.setVisibility(View.GONE);
        frameCount = 0;
        fpsDone = false;
        pending = -1;
        index = i;
        retries = 0; // cuba semula dikira bagi setiap saluran; "skips" sengaja TIDAK direset di sini
        resyncs = 0;
        triedHls = false;
        h.removeCallbacks(stable);
        Hub.playing = i;
        Store.setLastChannel(this, i);
        adapter.notifyDataSetChanged();
        status.setText("");
        play(Hub.channels.get(i), null);
    }

    private void play(Channel c, String forceMime) {
        if (!started || player == null) return; // onStart() akan mainkan semula
        firstFrame = false;
        playTicks = 0;
        frozenTicks = 0;
        lastFrames = -1;
        wasReady = false; // prepare semula bukan "tersekat"
        stallSince = 0;
        cushioning = false;
        h.removeCallbacks(cushionTick);
        decoderName = "";
        droppedBase = 0;
        String low = c.url.toLowerCase(java.util.Locale.ROOT);
        progressive = forceMime == null && c.drmType == null && !low.contains("m3u8") && !low.contains(".mpd");
        try {
            player.setMediaSource(Streams.source(this, c, forceMime));
            player.prepare();
            player.play();
        } catch (Exception e) {
            spinner.setVisibility(View.GONE);
            status.setText(c.name + " tidak dapat dimainkan.\n" + e);
        }
    }

    private void onError(PlaybackException e) {
        if (!started || player == null) return;
        if (index < 0 || index >= Hub.channels.size()) return;
        final Channel c = Hub.channels.get(index);
        final int failed = index;
        h.removeCallbacks(stable);
        // ralat dekoder / audio (kod 4xxx–5xxx) semasa tunneling: cuba mod biasa dulu, bukan salah saluran
        if (tunnelOn && e.errorCode >= 4000 && e.errorCode < 6000) {
            h.post(new Runnable() { // jangan lepaskan pemain dari dalam panggilan baliknya sendiri
                @Override
                public void run() {
                    if (started && tunnelOn) tunnelFallback();
                }
            });
            return;
        }
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && resyncs < MAX_RESYNCS) {
            resyncs++;
            player.seekToDefaultPosition();
            player.prepare();
            return;
        }
        if (!triedHls && (e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                || e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)) {
            triedHls = true;
            play(c, MimeTypes.APPLICATION_M3U8);
            return;
        }
        if (retries < MAX_RETRIES) {
            retries++;
            status.setText("Menyambung semula… (" + retries + ")");
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    // hanya jika masih saluran yang sama & tiada pilihan baharu menunggu
                    if (started && pending < 0 && index == failed && failed < Hub.channels.size())
                        play(Hub.channels.get(failed), triedHls ? MimeTypes.APPLICATION_M3U8 : null);
                }
            }, 1200L * retries);
            return;
        }
        c.alive = false;
        spinner.setVisibility(View.GONE);
        if (!Store.autoSkip(this) || Hub.channels.size() <= 1) {
            status.setText(c.name + " tidak dapat dimainkan.\n(" + e.getErrorCodeName() + ")\nTekan atas/bawah untuk saluran lain.");
        } else if (skips >= MAX_AUTO_SKIPS) {
            status.setText(MAX_AUTO_SKIPS + " saluran berturut-turut gagal – langkau automatik dihentikan.\n"
                    + "Semak sambungan Internet / akaun IPTV, atau tekan atas/bawah untuk pilih saluran.");
        } else {
            skips++;
            status.setText(c.name + " tidak tersedia sekarang.\nKe saluran seterusnya… (" + skips + "/" + MAX_AUTO_SKIPS + ")");
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (started && pending < 0 && index == failed) step(lastStep, true);
                }
            }, 1500);
        }
    }

    private void fit(VideoSize vs) {
        if (vs.width == 0 || vs.height == 0) return;
        float vw = vs.width * vs.pixelWidthHeightRatio, vh = vs.height;
        float bw = videoBox.getWidth(), bh = videoBox.getHeight();
        if (bw == 0 || bh == 0) return;
        float s = Math.min(bw / vw, bh / vh);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
        lp.width = Math.round(vw * s);
        lp.height = Math.round(vh * s);
        lp.gravity = Gravity.CENTER;
        surface.setLayoutParams(lp);
    }

    private void step(int delta, boolean auto) {
        int n = Hub.channels.size();
        if (n == 0) return;
        lastStep = delta;
        int base = pending >= 0 ? pending : Math.max(index, 0);
        select((base + delta + n) % n, 350, auto);
    }

    private void showPanel() {
        panel.setVisibility(View.VISIBLE);
        panel.setAlpha(0f);
        panel.setTranslationX(-S.px(200));
        panel.animate().translationX(0).alpha(1f).setDuration(240).start();
        list.requestFocus();
        list.setSelection(Math.max(0, index));
    }

    private void hidePanel() {
        panel.animate().translationX(-S.px(200)).alpha(0f).setDuration(200).withEndAction(new Runnable() {
            @Override
            public void run() {
                panel.setVisibility(View.GONE);
            }
        }).start();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        boolean panelOpen = panel.getVisibility() == View.VISIBLE;
        int k = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (k == KeyEvent.KEYCODE_GUIDE) {
                startActivity(new Intent(this, GuideActivity.class));
                return true;
            }
            if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) {
                digitBuf += (char) ('0' + k - KeyEvent.KEYCODE_0);
                if (digitBuf.length() > 4) digitBuf = digitBuf.substring(1);
                digits.setText(digitBuf);
                digits.setVisibility(View.VISIBLE);
                h.removeCallbacks(digitGo);
                h.postDelayed(digitGo, 1500);
                return true;
            }
            if (!panelOpen) {
                if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_CHANNEL_UP) {
                    step(1, false);
                    return true;
                }
                if (k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_CHANNEL_DOWN) {
                    step(-1, false);
                    return true;
                }
                if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_MENU
                        || k == KeyEvent.KEYCODE_DPAD_LEFT) {
                    showPanel();
                    return true;
                }
                if (k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_INFO) {
                    if (index >= 0) showInfo(index);
                    return true;
                }
            } else if (k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_BACK) {
                hidePanel();
                return true;
            }
        }
        if (e.getAction() == KeyEvent.ACTION_UP && k == KeyEvent.KEYCODE_BACK && panelOpen) return true;
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        fpsDone = false; // kesan semula & minta mod paparan bila kembali
        frameCount = 0;
        sessionStart = SystemClock.elapsedRealtime();
        stalls = 0;
        stallMs = 0;
        h.removeCallbacks(watchdog); // elak watchdog berganda selepas jeda/sambung berulang
        h.postDelayed(watchdog, 3000);
        h.postDelayed(hideInfo, 4500);
        if (player == null) return;
        if (needInitial) {
            needInitial = false;
            handle(getIntent(), true);
        } else if (pending >= 0 && pending < Hub.channels.size()) {
            int p = pending;
            select(p, 0, false);
        } else if (index >= 0 && index < Hub.channels.size()) {
            retries = 0;
            resyncs = 0;
            play(Hub.channels.get(index), triedHls ? MimeTypes.APPLICATION_M3U8 : null);
        }
    }

    @Override
    protected void onStop() {
        logSession();
        started = false;
        // Batal SEMUA panggilan tertangguh (watchdog, zap, cuba semula, langkau, nombor, sembunyi info)
        h.removeCallbacksAndMessages(null);
        digitBuf = "";
        digits.setVisibility(View.GONE);
        restoreRefreshRate(); // kembalikan kadar segar skrin asal untuk apl lain
        // stop() (bukan pause) supaya sambungan rangkaian/akaun IPTV dilepaskan semasa apl lain dibuka
        if (player != null) player.stop();
        bufferingSince = 0;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        started = false;
        h.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        Hub.playing = -1;
        super.onDestroy();
    }

    private final class ChAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return Hub.channels.size();
        }

        @Override
        public Object getItem(int p) {
            return Hub.channels.get(p);
        }

        @Override
        public long getItemId(int p) {
            return p;
        }

        @Override
        public View getView(int p, View v, ViewGroup parent) {
            LinearLayout row = (LinearLayout) v;
            if (row == null) {
                row = new LinearLayout(LiveTvActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(S.px(16), 0, S.px(16), 0);
                row.setLayoutParams(new ListView.LayoutParams(-1, S.px(84)));
                ImageView logo = new ImageView(LiveTvActivity.this);
                logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
                row.addView(logo, new LinearLayout.LayoutParams(S.px(96), S.px(56)));
                TextView num = Ui.text(LiveTvActivity.this, "", 24, 0xFF64B5FF, Ui.BOLD);
                num.setGravity(Gravity.END);
                LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(S.px(70), -2);
                nlp.rightMargin = S.px(18);
                row.addView(num, nlp);
                LinearLayout txt = new LinearLayout(LiveTvActivity.this);
                txt.setOrientation(LinearLayout.VERTICAL);
                txt.addView(Ui.text(LiveTvActivity.this, "", 28, Ui.WHITE, Ui.MEDIUM));
                txt.addView(Ui.text(LiveTvActivity.this, "", 20, Ui.DIM, Ui.MEDIUM));
                row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));
            }
            Channel c = Hub.channels.get(p);
            Img.load((ImageView) row.getChildAt(0), c.logo, S.px(96));
            ((TextView) row.getChildAt(1)).setText(String.valueOf(p + 1));
            LinearLayout txt = (LinearLayout) row.getChildAt(2);
            ((TextView) txt.getChildAt(0)).setText(c.name);
            ((TextView) txt.getChildAt(0)).setTextColor(p == index ? 0xFF64B5FF : Boolean.FALSE.equals(c.alive) ? Ui.FAINT : Ui.WHITE);
            ((TextView) txt.getChildAt(1)).setText((p == index ? "▶ Sedang dimainkan  " : "")
                    + (Boolean.FALSE.equals(c.alive) ? "Tidak tersedia  " : "") + c.group);
            return row;
        }
    }
}
