package com.aqil.launcher;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TextClock;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Panduan TV: grid jadual saluran untuk 3 jam ke depan. Jadual diambil secara malas (hanya saluran yang
 * dipaparkan) dari EPG akaun IPTV. OK pada rancangan yang sedang berlangsung / saluran = tonton.
 */
public class GuideActivity extends BaseActivity {
    private static final int PER_PAGE = 30;
    private static final long WINDOW = 3 * 3600_000L;
    private static final float X0 = 420, W = 1440; // kawasan masa (unit reka bentuk) dalam baris
    private static final Map<Integer, List<Xtream.Prog>> CACHE = new HashMap<>();
    private static final Map<Integer, Long> CACHED_AT = new HashMap<>();
    private final ExecutorService io = Executors.newFixedThreadPool(3);

    private long t0;
    private int pageStart;
    private ScrollView sv;
    private LinearLayout rows;
    private TextView dTitle, dTime, dDesc;
    private View nowLine;
    private boolean alive;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        alive = true;
        long now = System.currentTimeMillis();
        t0 = now - now % (30 * 60_000L); // bundar ke bawah 30 minit
        int n = Hub.channels.size();
        pageStart = n == 0 ? 0 : (Math.max(0, Math.min(Store.lastChannel(this), n - 1)) / PER_PAGE) * PER_PAGE;

        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF070B1A, 0xFF120C26, 0xFF1A0F2E}));
        root.addView(Ui.text(this, "Panduan TV", 46, Ui.WHITE, Ui.BOLD), Ui.at(80, 36, -2, -2));
        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("EEEE, d MMM  •  HH:mm");
        clock.setFormat12Hour("EEEE, d MMM  •  h:mm a");
        clock.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, S.px(26));
        clock.setTextColor(Ui.DIM);
        root.addView(clock, Ui.at(82, 98, -2, -2));
        root.addView(Ui.text(this, "OK pada rancangan sekarang = tonton  •  Back = keluar", 22, Ui.FAINT, Ui.MEDIUM), Ui.at(1100, 104, 760, -2));

        // pembaris masa
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.ROOT);
        for (int i = 0; i < 6; i++) {
            TextView t = Ui.text(this, hm.format(new Date(t0 + i * 30 * 60_000L)), 22, Ui.DIM, Ui.MEDIUM);
            root.addView(t, Ui.at(40 + X0 + i * W / 6 + 8, 158, 200, -2));
            View tick = new View(this);
            tick.setBackgroundColor(0x33FFFFFF);
            root.addView(tick, Ui.at(40 + X0 + i * W / 6, 156, 2, 30));
        }

        sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rows = new FrontLayout(this, LinearLayout.VERTICAL);
        rows.setPadding(0, S.px(10), 0, S.px(40));
        Ui.clipToBounds(sv);
        sv.addView(rows);
        root.addView(sv, Ui.at(40, 196, 1860, 700));

        // garis "sekarang"
        nowLine = new View(this);
        nowLine.setBackground(Ui.solid(0xFFE5263B, S.px(2)));
        root.addView(nowLine, Ui.at(40 + X0 + (now - t0) * W / WINDOW, 186, 4, 710));

        // butiran rancangan
        FrameLayout det = new FrameLayout(this);
        det.setBackground(Ui.glass(S.px(26)));
        dTitle = Ui.text(this, "", 32, Ui.WHITE, Ui.BOLD);
        det.addView(dTitle, Ui.at(30, 18, 1500, -2));
        dTime = Ui.text(this, "", 22, 0xFF64B5FF, Ui.MEDIUM);
        det.addView(dTime, Ui.at(32, 64, 1500, -2));
        dDesc = Ui.text(this, "", 22, Ui.DIM, Ui.MEDIUM);
        dDesc.setSingleLine(false);
        dDesc.setMaxLines(2);
        det.addView(dDesc, Ui.at(32, 98, 1780, -2));
        root.addView(det, Ui.at(40, 912, 1840, 150));
        setContentView(root);
        buildPage();
    }

    private void buildPage() {
        rows.removeAllViews();
        List<Channel> chs = Hub.channels;
        if (chs.isEmpty()) {
            dTitle.setText("Tiada saluran");
            return;
        }
        if (pageStart > 0) rows.addView(pager("‹  Saluran sebelum", pageStart - PER_PAGE), new LinearLayout.LayoutParams(S.px(400), S.px(70)));
        View focusTarget = null;
        int end = Math.min(chs.size(), pageStart + PER_PAGE);
        for (int i = pageStart; i < end; i++) {
            View r = row(i, chs.get(i));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, S.px(88));
            lp.bottomMargin = S.px(8);
            rows.addView(r, lp);
            if (i == Store.lastChannel(this) || focusTarget == null) focusTarget = ((FrameLayout) r).getChildAt(0);
        }
        if (end < chs.size()) rows.addView(pager("Saluran seterusnya  ›", end), new LinearLayout.LayoutParams(S.px(400), S.px(70)));
        final View f = focusTarget;
        if (f != null) rows.post(f::requestFocus);
    }

    private Row pager(String label, final int start) {
        Row r = new Row(this, label, null, null);
        r.flat();
        r.setOnClickListener(v -> {
            pageStart = Math.max(0, start);
            buildPage();
            sv.scrollTo(0, 0);
        });
        return r;
    }

    private View row(final int index, final Channel ch) {
        final FrameLayout row = new FrameLayout(this);
        Ui.noClip(row);
        Card cell = new Card(this, 18).flat();
        cell.scaleTo = 1.04f;
        ImageView logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Img.load(logo, ch.logo, S.px(110));
        cell.addView(logo, Ui.at(14, 12, 100, 60));
        cell.addView(Ui.text(this, String.valueOf(index + 1), 20, 0xFF64B5FF, Ui.BOLD), Ui.at(128, 14, 70, -2));
        cell.addView(Ui.text(this, ch.name, 22, Ui.WHITE, Ui.MEDIUM), Ui.at(128, 44, 250, -2));
        cell.setOnClickListener(v -> watch(index));
        cell.onFocus = (c, gained) -> {
            if (gained) {
                scrollTo(row);
                detail(ch.name, "Saluran " + (index + 1) + (ch.group.isEmpty() ? "" : "  •  " + ch.group), "OK = tonton saluran ini");
            }
        };
        row.addView(cell, Ui.at(0, 0, X0 - 20, 84));

        if (ch.xtId == 0) {
            row.addView(block(index, ch, null, t0, t0 + WINDOW, "Tiada jadual untuk saluran ini"), blockLp(t0, t0 + WINDOW));
            return row;
        }
        List<Xtream.Prog> cached = cached(ch.xtId);
        if (cached != null) fill(row, index, ch, cached);
        else {
            TextView wait = Ui.text(this, "Memuatkan jadual…", 20, Ui.FAINT, Ui.MEDIUM);
            row.addView(wait, Ui.at(X0 + 10, 30, 600, -2));
            io.execute(() -> {
                final List<Xtream.Prog> l = Xtream.epg(this, ch, 10);
                synchronized (CACHE) {
                    CACHE.put(ch.xtId, l);
                    CACHED_AT.put(ch.xtId, System.currentTimeMillis());
                }
                Hub.main.post(() -> {
                    if (!alive || row.getParent() == null) return;
                    row.removeView(wait);
                    fill(row, index, ch, l);
                });
            });
        }
        return row;
    }

    private static List<Xtream.Prog> cached(int id) {
        synchronized (CACHE) {
            Long at = CACHED_AT.get(id);
            if (at == null || System.currentTimeMillis() - at > 15 * 60_000L) return null;
            return CACHE.get(id);
        }
    }

    private void fill(FrameLayout row, int index, Channel ch, List<Xtream.Prog> l) {
        long end = t0 + WINDOW;
        boolean any = false;
        for (Xtream.Prog p : l) {
            if (p.end <= t0 || p.start >= end || p.start <= 0) continue;
            row.addView(block(index, ch, p, Math.max(p.start, t0), Math.min(p.end, end), p.title), blockLp(Math.max(p.start, t0), Math.min(p.end, end)));
            any = true;
        }
        if (!any) row.addView(block(index, ch, null, t0, end, "Tiada jadual untuk tempoh ini"), blockLp(t0, end));
    }

    private FrameLayout.LayoutParams blockLp(long a, long b) {
        float x = X0 + (a - t0) * W / WINDOW, w = Math.max(40, (b - a) * W / WINDOW - 8);
        return Ui.at(x, 0, w, 84);
    }

    private Card block(final int index, final Channel ch, final Xtream.Prog p, long a, long b, String label) {
        final long now = System.currentTimeMillis();
        final boolean airing = p == null || (p.start <= now && p.end > now);
        Card c = new Card(this, 16, airing ? Ui.grad(GradientDrawable.Orientation.TOP_BOTTOM, S.px(16), 0x663A5BD9, 0x55223A8F)
                : Ui.glass(S.px(16))).flat();
        c.scaleTo = 1.03f;
        TextView t = Ui.text(this, label, 21, Ui.WHITE, Ui.MEDIUM);
        c.addView(t, Ui.at(14, 12, Math.max(30, (b - a) * W / WINDOW - 36), -2));
        if (p != null) {
            SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.ROOT);
            c.addView(Ui.text(this, hm.format(new Date(p.start)) + "–" + hm.format(new Date(p.end)), 17, Ui.DIM, Ui.MEDIUM),
                    Ui.at(14, 48, Math.max(30, (b - a) * W / WINDOW - 36), -2));
        }
        c.onFocus = (card, gained) -> {
            if (!gained) return;
            scrollTo((View) card.getParent());
            if (p == null) detail(ch.name, "Saluran " + (index + 1), label + "  •  OK = tonton");
            else {
                SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.ROOT);
                detail(p.title, ch.name + "  •  " + hm.format(new Date(p.start)) + "–" + hm.format(new Date(p.end))
                        + (airing ? "  •  SEDANG BERLANGSUNG" : ""), p.desc.isEmpty() ? (airing ? "OK = tonton sekarang" : "") : p.desc);
            }
        };
        c.setOnClickListener(v -> {
            if (airing) watch(index);
            else Toast.makeText(this, "Rancangan ini bermula " + new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(p.start)), Toast.LENGTH_SHORT).show();
        });
        return c;
    }

    private void scrollTo(View row) {
        if (row != null) sv.smoothScrollTo(0, Math.max(0, row.getTop() - S.px(180)));
    }

    private void detail(String title, String time, String desc) {
        dTitle.setText(title);
        dTime.setText(time);
        dDesc.setText(desc);
    }

    private void watch(int index) {
        startActivity(new Intent(this, LiveTvActivity.class).putExtra("index", index)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_GUIDE) {
            finish();
            return true;
        }
        return super.onKeyUp(keyCode, e);
    }

    @Override
    protected void onDestroy() {
        alive = false;
        io.shutdownNow();
        super.onDestroy();
    }
}
