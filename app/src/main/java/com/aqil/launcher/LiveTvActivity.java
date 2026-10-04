package com.aqil.launcher;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.VideoView;

import java.util.List;

/** Siaran langsung: senarai M3U/IPTV. Atas/bawah tukar saluran, OK buka senarai, remote telefon boleh pilih. */
public class LiveTvActivity extends BaseActivity {
    private VideoView video;
    private FrameLayout panel;
    private ListView list;
    private TextView info, status;
    private ChAdapter adapter;
    private float d;
    private int index = -1;
    private final Runnable hideInfo = new Runnable() {
        @Override
        public void run() {
            info.animate().alpha(0f).setDuration(400).start();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = U.dp(this, 1);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        info = new TextView(this) {
            @Override
            protected void onDraw(android.graphics.Canvas c) {
                Glass.plate(c, d, 0, 0, getWidth(), getHeight(), 22 * d, 0f, 0, 0, -1f);
                super.onDraw(c);
            }
        };
        info.setTextColor(Color.WHITE);
        info.setTextSize(20);
        info.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        info.setPadding((int) (26 * d), (int) (14 * d), (int) (26 * d), (int) (14 * d));
        info.setAlpha(0f);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.BOTTOM);
        ilp.setMargins((int) (48 * d), 0, 0, (int) (48 * d));
        root.addView(info, ilp);

        status = new TextView(this);
        status.setTextColor(0xCCFFFFFF);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        // panel senarai di kiri
        panel = new FrameLayout(this) {
            @Override
            protected void onDraw(android.graphics.Canvas c) {
                c.drawColor(0x66000000);
                Glass.plate(c, d, 16 * d, 16 * d, getWidth() - 16 * d, getHeight() - 16 * d, 30 * d, 0f, 0, 0, -1f);
            }
        };
        panel.setWillNotDraw(false);
        panel.setPadding((int) (34 * d), (int) (34 * d), (int) (34 * d), (int) (34 * d));
        panel.setVisibility(View.GONE);
        list = new ListView(this);
        list.setDivider(null);
        list.setVerticalScrollBarEnabled(false);
        list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        adapter = new ChAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                play(pos);
                hidePanel();
            }
        });
        panel.addView(list, new FrameLayout.LayoutParams(-1, -1));
        root.addView(panel, new FrameLayout.LayoutParams((int) (440 * d), -1, Gravity.START));
        setContentView(root);

        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                status.setText("");
                mp.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                    @Override
                    public boolean onInfo(MediaPlayer m, int what, int extra) {
                        if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) status.setText("Memuatkan…");
                        else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START)
                            status.setText("");
                        return true;
                    }
                });
            }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                status.setText("Saluran ini tak dapat dimainkan.\nTekan atas/bawah untuk saluran lain.");
                return true;
            }
        });

        status.setText("Memuatkan senarai saluran…");
        Hub.loadChannels(false, new Hub.Done() {
            @Override
            public void run(int count, String error) {
                if (isFinishing()) return;
                adapter.notifyDataSetChanged();
                if (count == 0) {
                    status.setText("Tiada saluran.\n" + (error == null ? "" : error)
                            + "\nTetapkan URL senarai M3U dari telefon (Remote > Live TV).");
                    return;
                }
                handleIntent(getIntent(), true);
            }
        });
    }

    @Override
    public void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleIntent(i, false);
    }

    private void handleIntent(Intent i, boolean first) {
        List<Channel> chs = Hub.channels;
        if (chs.isEmpty()) return;
        int idx = i.getIntExtra("index", first ? Store.lastChannel(this) : -1);
        if (idx >= 0) play(Math.min(idx, chs.size() - 1));
        else if (index < 0) play(0);
    }

    private void play(int i) {
        List<Channel> chs = Hub.channels;
        if (i < 0 || i >= chs.size()) return;
        index = i;
        Hub.playing = i;
        Store.setLastChannel(this, i);
        Channel c = chs.get(i);
        status.setText("Memuatkan…");
        video.stopPlayback();
        video.setVideoURI(Uri.parse(c.url));
        video.start();
        info.setText((i + 1) + "   " + c.name);
        info.animate().cancel();
        info.setAlpha(1f);
        info.removeCallbacks(hideInfo);
        info.postDelayed(hideInfo, 3500);
        adapter.notifyDataSetChanged();
        list.setSelection(Math.max(0, i - 3));
    }

    private void step(int delta) {
        int n = Hub.channels.size();
        if (n == 0) return;
        play((index + delta + n) % n);
    }

    private void showPanel() {
        panel.setVisibility(View.VISIBLE);
        panel.setTranslationX(-panel.getWidth() == 0 ? -400 * d : -panel.getWidth());
        panel.setAlpha(0f);
        panel.animate().translationX(0).alpha(1f).setDuration(260).start();
        list.requestFocus();
        list.setSelection(Math.max(0, index));
    }

    private void hidePanel() {
        panel.animate().translationX(-panel.getWidth()).alpha(0f).setDuration(200).withEndAction(new Runnable() {
            @Override
            public void run() {
                panel.setVisibility(View.GONE);
            }
        }).start();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        boolean panelOpen = panel.getVisibility() == View.VISIBLE;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            int k = e.getKeyCode();
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
            } else if (k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_BACK) {
                hidePanel();
                return true;
            }
        }
        if (e.getAction() == KeyEvent.ACTION_UP && e.getKeyCode() == KeyEvent.KEYCODE_BACK && panelOpen) return true;
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onDestroy() {
        video.stopPlayback();
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
            GlassButton b = v instanceof GlassButton ? (GlassButton) v : new GlassButton(LiveTvActivity.this, "", null, 0, 58);
            if (!(v instanceof GlassButton)) b.setFocusable(false);
            Channel c = Hub.channels.get(p);
            b.set((p + 1) + "   " + c.name, c.group.isEmpty() ? null : c.group, null);
            b.setMarked(p == index);
            return b;
        }
    }
}
