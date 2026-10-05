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
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

import java.util.Locale;

/**
 * Pemain Filem & Siri. OK = main/jeda, kiri/kanan = undur 10s / maju 30s (tahan = lebih laju),
 * atas/bawah = papar kawalan, Back = keluar (kedudukan disimpan untuk "Sambung tonton").
 * Siri: episod seterusnya dimainkan automatik selepas kiraan 8 saat.
 */
@OptIn(markerClass = UnstableApi.class)
public class VodPlayerActivity extends BaseActivity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private SurfaceView surface;
    private FrameLayout videoBox, bar;
    private View track, fill;
    private TextView title, time, state, status, nextBox;
    private ProgressBar spinner;
    private String[] urls, titles;
    private String icon;
    private int index;
    private boolean started;
    private long resumeAt = -1, seekTarget = -1;
    private int countdown;

    private final Runnable hideBar = new Runnable() {
        @Override
        public void run() {
            if (player != null && player.isPlaying()) bar.animate().alpha(0f).setDuration(300).start();
        }
    };
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!started || player == null) return;
            updateBar();
            h.postDelayed(this, 500);
        }
    };
    private final Runnable save = new Runnable() {
        @Override
        public void run() {
            savePos();
            if (started) h.postDelayed(this, 10000);
        }
    };
    private final Runnable nextTick = new Runnable() {
        @Override
        public void run() {
            if (!started) return;
            if (countdown <= 0) {
                playIndex(index + 1, false);
                return;
            }
            nextBox.setText("Episod seterusnya dalam " + countdown + " s\n" + titles[index + 1] + "\nOK = main sekarang  •  Back = batal");
            countdown--;
            h.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent i = getIntent();
        urls = i.getStringArrayExtra("urls");
        titles = i.getStringArrayExtra("titles");
        index = i.getIntExtra("index", 0);
        icon = i.getStringExtra("icon");
        if (urls == null || urls.length == 0 || titles == null || titles.length != urls.length) {
            finish();
            return;
        }
        index = Math.max(0, Math.min(index, urls.length - 1));

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

        // ---- bar kawalan (bawah)
        bar = new FrameLayout(this);
        bar.setBackground(Ui.grad(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, 0, 0xE6000000, 0x00000000));
        title = Ui.text(this, "", 40, Ui.WHITE, Ui.BOLD);
        bar.addView(title, Ui.at(80, 60, 1500, -2));
        state = Ui.text(this, "", 26, Ui.DIM, Ui.MEDIUM);
        bar.addView(state, Ui.at(80, 118, 1100, -2));
        track = new View(this);
        track.setBackground(Ui.solid(0x40FFFFFF, S.px(4)));
        bar.addView(track, Ui.at(80, 172, 1760, 8));
        fill = new View(this);
        fill.setBackground(Ui.solid(0xFF2E8BFF, S.px(4)));
        bar.addView(fill, Ui.at(80, 172, 0, 8));
        time = Ui.text(this, "", 26, Ui.WHITE, Ui.MEDIUM);
        time.setGravity(Gravity.END);
        bar.addView(time, Ui.at(1240, 118, 600, -2));
        root.addView(bar, new FrameLayout.LayoutParams(-1, S.px(240), Gravity.BOTTOM));

        nextBox = Ui.text(this, "", 26, Ui.WHITE, Ui.MEDIUM);
        nextBox.setSingleLine(false);
        nextBox.setBackground(Ui.glass(S.px(24)));
        nextBox.setPadding(S.px(30), S.px(20), S.px(30), S.px(20));
        nextBox.setVisibility(View.GONE);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(S.px(640), -2, Gravity.END | Gravity.BOTTOM);
        nlp.rightMargin = S.px(80);
        nlp.bottomMargin = S.px(260);
        root.addView(nextBox, nlp);
        setContentView(root);

        player = Streams.player(this, false);
        player.setVideoSurfaceView(surface);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int st) {
                spinner.setVisibility(st == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                if (st == Player.STATE_READY) {
                    status.setText("");
                    if (resumeAt > 0) {
                        player.seekTo(resumeAt);
                        resumeAt = -1;
                    }
                } else if (st == Player.STATE_ENDED) {
                    onEnded();
                }
                updateBar();
            }

            @Override
            public void onIsPlayingChanged(boolean playing) {
                updateBar();
                showBar();
            }

            @Override
            public void onVideoSizeChanged(VideoSize vs) {
                fit(vs);
            }

            @Override
            public void onPlayerError(PlaybackException e) {
                spinner.setVisibility(View.GONE);
                status.setText("Tak dapat dimainkan.\n(" + e.getErrorCodeName() + ")");
            }
        });
        playIndex(index, true);
    }

    private String url() {
        return urls[index];
    }

    /** Pilih episod/filem; resume=true sambung dari kedudukan tersimpan. */
    private void playIndex(int i, boolean resume) {
        if (i < 0 || i >= urls.length) return;
        if (player != null && index != i) savePos();
        h.removeCallbacks(nextTick);
        nextBox.setVisibility(View.GONE);
        index = i;
        title.setText(titles[i]);
        long saved = Store.vodPos(this, url());
        resumeAt = resume && saved > 30_000 ? saved : -1;
        if (resumeAt > 0) Toast.makeText(this, "Sambung dari " + fmt(resumeAt) + "  (tekan kiri untuk undur)", Toast.LENGTH_LONG).show();
        status.setText("");
        startPlayback();
    }

    /** Mainkan item semasa (hanya bila aktiviti kelihatan; onStart() memanggil semula). */
    private void startPlayback() {
        if (!started || player == null) return;
        Channel ch = new Channel(titles[index], url(), "", "");
        try {
            player.setMediaSource(Streams.source(this, ch, null));
            player.prepare();
            player.play();
        } catch (Exception e) {
            status.setText("Tak dapat dimainkan.\n" + e.getMessage());
        }
        showBar();
    }

    private void onEnded() {
        savePos();
        if (index + 1 < urls.length) {
            countdown = 8;
            nextBox.setVisibility(View.VISIBLE);
            h.removeCallbacks(nextTick);
            h.post(nextTick);
        } else {
            status.setText("Tamat.  Tekan Back untuk keluar.");
        }
    }

    private void savePos() {
        if (player == null) return;
        long pos = player.getCurrentPosition(), dur = player.getDuration();
        if (dur <= 0 || pos <= 0) return;
        Store.saveVod(this, titles[index], url(), icon, pos, dur);
    }

    private void showBar() {
        bar.animate().cancel();
        bar.setAlpha(1f);
        h.removeCallbacks(hideBar);
        h.postDelayed(hideBar, 4000);
    }

    private void updateBar() {
        if (player == null) return;
        long dur = player.getDuration(), pos = seekTarget >= 0 ? seekTarget : player.getCurrentPosition();
        boolean playing = player.isPlaying();
        state.setText((playing ? "▶  Sedang main" : "❚❚  Dijeda") + (urls.length > 1 ? "   •   Episod " + (index + 1) + " / " + urls.length : ""));
        time.setText(fmt(pos) + (dur > 0 ? "  /  " + fmt(dur) : ""));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) fill.getLayoutParams();
        lp.width = dur > 0 ? Math.round(S.px(1760) * U.clamp(pos / (float) dur, 0, 1)) : 0;
        fill.setLayoutParams(lp);
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
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

    /** Undur/maju: tekan sekali 10s/30s; tahan → langkah makin besar. */
    private void seek(boolean forward, int repeat) {
        if (player == null || player.getDuration() <= 0) return;
        long step = (forward ? 30_000 : 10_000) * (1 + Math.min(repeat / 4, 9));
        long base = seekTarget >= 0 ? seekTarget : player.getCurrentPosition();
        seekTarget = Math.max(0, Math.min(player.getDuration() - 1000, base + (forward ? step : -step)));
        updateBar();
        showBar();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    if (e.getRepeatCount() > 0) return true;
                    if (nextBox.getVisibility() == View.VISIBLE) {
                        playIndex(index + 1, false);
                        return true;
                    }
                    if (player != null) {
                        if (player.getPlaybackState() == Player.STATE_ENDED) player.seekTo(0);
                        if (player.isPlaying()) player.pause();
                        else player.play();
                    }
                    showBar();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    seek(false, e.getRepeatCount());
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    seek(true, e.getRepeatCount());
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_INFO:
                    showBar();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    if (index + 1 < urls.length) playIndex(index + 1, false);
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (nextBox.getVisibility() == View.VISIBLE) {
                        h.removeCallbacks(nextTick);
                        nextBox.setVisibility(View.GONE);
                        return true;
                    }
                    break;
                default:
                    break;
            }
        } else if (e.getAction() == KeyEvent.ACTION_UP) {
            if ((k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                    || k == KeyEvent.KEYCODE_MEDIA_REWIND || k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) && seekTarget >= 0) {
                if (player != null) player.seekTo(seekTarget); // lompat sekali bila butang dilepas
                seekTarget = -1;
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        h.post(tick);
        h.postDelayed(save, 10000);
        if (player != null && player.getPlaybackState() == Player.STATE_IDLE) {
            if (resumeAt < 0) { // kembali dari apl lain: sambung dari kedudukan yang disimpan dalam onStop()
                long saved = Store.vodPos(this, url());
                if (saved > 30_000) resumeAt = saved;
            }
            startPlayback();
        }
    }

    @Override
    protected void onStop() {
        started = false;
        savePos();
        h.removeCallbacksAndMessages(null);
        if (player != null) player.stop(); // lepas sambungan akaun semasa apl lain dibuka
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
