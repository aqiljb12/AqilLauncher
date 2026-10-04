package com.aqil.launcher;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
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
    private ExoPlayer player;
    private SurfaceView surface;
    private FrameLayout videoBox, panel, info;
    private ListView list;
    private TextView infoNum, infoName, infoGroup, status, digits;
    private ImageView infoLogo;
    private ProgressBar spinner;
    private ChAdapter adapter;
    private int index = -1, pending = -1, retries, skips, lastStep = 1;
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
            if (pending >= 0) start(pending);
        }
    };
    private final Runnable digitGo = new Runnable() {
        @Override
        public void run() {
            int n = digitBuf.isEmpty() ? 0 : Integer.parseInt(digitBuf);
            digitBuf = "";
            digits.setVisibility(View.GONE);
            if (n >= 1 && n <= Hub.channels.size()) select(n - 1, 0);
        }
    };
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (player != null && player.getPlaybackState() == Player.STATE_BUFFERING && bufferingSince > 0
                    && System.currentTimeMillis() - bufferingSince > 15000) {
                // tersekat terlalu lama: sambung semula ke hujung siaran langsung
                bufferingSince = System.currentTimeMillis();
                player.seekToDefaultPosition();
                player.prepare();
            }
            status.postDelayed(this, 3000);
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
                select(pos, 0);
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
                if (isFinishing()) return;
                adapter.notifyDataSetChanged();
                if (count == 0) {
                    spinner.setVisibility(View.GONE);
                    status.setText("Tiada saluran.\n" + (error == null ? "" : error)
                            + "\nTetapkan URL senarai M3U dari telefon (Remote › Lagi).");
                    return;
                }
                handle(getIntent(), true);
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
                    retries = 0;
                    skips = 0;
                    if (index >= 0 && index < Hub.channels.size()) Hub.channels.get(index).alive = true;
                }
            }

            @Override
            public void onVideoSizeChanged(VideoSize vs) {
                fit(vs);
            }

            @Override
            public void onPlayerError(PlaybackException e) {
                onError(e);
            }
        });
        status.postDelayed(watchdog, 3000);
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
        if (idx >= 0) select(Math.min(idx, chs.size() - 1), 0);
    }

    /** Pilih saluran: papar maklumat serta-merta, mula main selepas 350ms (zapping laju tanpa sangkut). */
    private void select(int i, int delayMs) {
        List<Channel> chs = Hub.channels;
        if (i < 0 || i >= chs.size()) return;
        pending = i;
        showInfo(i);
        status.removeCallbacks(zap);
        if (delayMs <= 0) start(i);
        else status.postDelayed(zap, delayMs);
    }

    private void showInfo(int i) {
        Channel c = Hub.channels.get(i);
        infoNum.setText(String.valueOf(i + 1));
        infoName.setText(c.name);
        infoGroup.setText(c.group);
        if (c.xtId != 0) {
            final Channel ch = c;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final String s = Xtream.nowNext(LiveTvActivity.this, ch);
                    if (s != null) runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (index >= 0 && index < Hub.channels.size() && (Hub.channels.get(index) == ch || pending >= 0 && Hub.channels.get(pending) == ch))
                                infoGroup.setText(s);
                        }
                    });
                }
            }).start();
        }
        Img.load(infoLogo, c.logo, S.px(170));
        info.animate().cancel();
        info.setAlpha(1f);
        info.setTranslationY(0);
        info.removeCallbacks(hideInfo);
        info.postDelayed(hideInfo, 4500);
    }

    private void start(int i) {
        pending = -1;
        index = i;
        retries = 0;
        triedHls = false;
        Hub.playing = i;
        Store.setLastChannel(this, i);
        adapter.notifyDataSetChanged();
        status.setText("");
        play(Hub.channels.get(i), null);
    }

    private void play(Channel c, String forceMime) {
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
        if (index < 0 || index >= Hub.channels.size()) return;
        Channel c = Hub.channels.get(index);
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
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
        if (retries < 2) {
            retries++;
            status.setText("Menyambung semula… (" + retries + ")");
            status.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (index >= 0 && pending < 0) play(Hub.channels.get(index), triedHls ? MimeTypes.APPLICATION_M3U8 : null);
                }
            }, 1200L * retries);
            return;
        }
        c.alive = false;
        spinner.setVisibility(View.GONE);
        if (Store.autoSkip(this) && skips < 15 && Hub.channels.size() > 1) {
            skips++;
            status.setText(c.name + " tidak tersedia sekarang.\nKe saluran seterusnya…");
            status.postDelayed(new Runnable() {
                @Override
                public void run() {
                    step(lastStep);
                }
            }, 1500);
        } else {
            status.setText(c.name + " tidak dapat dimainkan.\n(" + e.getErrorCodeName() + ")\nTekan atas/bawah untuk saluran lain.");
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

    private void step(int delta) {
        int n = Hub.channels.size();
        if (n == 0) return;
        lastStep = delta;
        int base = pending >= 0 ? pending : Math.max(index, 0);
        select((base + delta + n) % n, 350);
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
                digits.removeCallbacks(digitGo);
                digits.postDelayed(digitGo, 1500);
                return true;
            }
            if (!panelOpen) {
                if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_CHANNEL_UP) {
                    step(1);
                    return true;
                }
                if (k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_CHANNEL_DOWN) {
                    step(-1);
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
    protected void onStop() {
        super.onStop();
        if (player != null) player.pause();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (player != null && index >= 0) {
            player.seekToDefaultPosition();
            player.prepare();
            player.play();
        }
    }

    @Override
    protected void onDestroy() {
        status.removeCallbacks(watchdog);
        status.removeCallbacks(zap);
        if (player != null) player.release();
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
