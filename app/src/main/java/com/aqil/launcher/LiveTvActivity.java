package com.aqil.launcher;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import androidx.media3.exoplayer.ExoPlayer;

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
    private TextView infoNum, infoName, infoGroup, status, digits, resLabel;
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
            }
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
                    final String s = Xtream.nowNext(LiveTvActivity.this, ch);
                    if (s != null) runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!isDestroyed() && epgFor == ch) infoGroup.setText(s);
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
        infoName = Ui.text(this, "", 40, Ui.WHITE, Ui.BOLD);
        info.addView(infoName, Ui.at(224, 64, 620, -2));
        infoGroup = Ui.text(this, "", 24, Ui.DIM, Ui.MEDIUM);
        info.addView(infoGroup, Ui.at(224, 116, 620, -2));
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(S.px(880), S.px(164), Gravity.START | Gravity.BOTTOM);
        ilp.leftMargin = S.px(70);
        ilp.bottomMargin = S.px(60);
        info.setAlpha(0f);
        root.addView(info, ilp);

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
        player = Streams.player(this, false);
        player.setVideoSurfaceView(surface);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) {
                    if (bufferingSince == 0) bufferingSince = System.currentTimeMillis();
                    spinner.setVisibility(View.VISIBLE);
                } else {
                    bufferingSince = 0;
                    spinner.setVisibility(View.GONE);
                }
                if (state == Player.STATE_READY) {
                    status.setText("");
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
        if (c.xtId != 0) h.postDelayed(epgFetch, 700);
        Img.load(infoLogo, c.logo, S.px(170));
        info.animate().cancel();
        info.setAlpha(1f);
        info.setTranslationY(0);
        h.removeCallbacks(hideInfo);
        h.postDelayed(hideInfo, 4500);
    }

    private void start(int i) {
        if (i < 0 || i >= Hub.channels.size()) return;
        resLabel.setVisibility(View.GONE);
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
        started = false;
        // Batal SEMUA panggilan tertangguh (watchdog, zap, cuba semula, langkau, nombor, sembunyi info)
        h.removeCallbacksAndMessages(null);
        digitBuf = "";
        digits.setVisibility(View.GONE);
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
