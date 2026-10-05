package com.aqil.launcher;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Filem & Siri dari akaun IPTV: grid poster ikut kategori, carian, sambung tonton, butiran + episod. */
final class MoviesPage extends Page {
    private static final int PER_PAGE = 48, COLS = 8;
    private String kind = Vod.MOVIE, catId, catName, query;
    private List<String[]> cats;
    private List<Vod.Item> items;
    private boolean loading;
    private String error;
    private int pageNo, gen;
    // butiran
    private Vod.Item open;
    private Vod.Detail detail;
    private int season = -1;
    private String lastFocusId;

    MoviesPage(MainActivity a) {
        super(a);
    }

    @Override
    boolean onBack() {
        if (open != null) {
            open = null;
            detail = null;
            build();
            refocus();
            return true;
        }
        if (query != null) {
            query = null;
            pageNo = 0;
            load();
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- data

    private void load() {
        final int g = ++gen;
        loading = true;
        error = null;
        build();
        final String k = kind, cid = catId, q = query;
        Vod.IO.execute(() -> {
            List<String[]> c = null;
            List<Vod.Item> l = null;
            String err = null;
            try {
                c = Vod.categories(a, k);
                if (q != null) l = Vod.search(a, k, q);
                else {
                    String use = cid;
                    if (use == null && !c.isEmpty()) use = c.get(0)[0];
                    l = Vod.items(a, k, use);
                }
            } catch (Exception e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final List<String[]> fc = c;
            final List<Vod.Item> fl = l;
            final String fe = err;
            Hub.main.post(() -> {
                if (g != gen) return;
                loading = false;
                error = fe;
                cats = fc;
                items = fl;
                if (catId == null && cats != null && !cats.isEmpty() && query == null) {
                    catId = cats.get(0)[0];
                    catName = cats.get(0)[1];
                }
                if (root.isAttachedToWindow() && open == null) {
                    build();
                    refocus();
                }
            });
        });
    }

    private void refocus() {
        View t = lastFocusId == null ? null : root.findViewWithTag(lastFocusId);
        if (t != null) t.requestFocus();
        else if (firstView != null) firstView.requestFocus();
    }

    // ---------------------------------------------------------------- bina

    @Override
    void build() {
        root.removeAllViews();
        firstView = null;
        if (Store.xtream(a) == null) {
            root.addView(Ui.text(a, "Filem & Siri", 46, Ui.WHITE, Ui.BOLD), Ui.at(6, 0, -2, -2));
            TextView t = Ui.text(a, "Filem dan siri datang dari akaun IPTV (Xtream / Smarters).\nLog masuk akaun dahulu di Tetapan › Live TV, atau dari telefon (Lagi › Akaun IPTV).", 28, Ui.DIM, Ui.MEDIUM);
            t.setSingleLine(false);
            root.addView(t, Ui.at(8, 90, 1400, -2));
            Row go = new Row(a, "Buka Tetapan", null, null);
            go.setOnClickListener(v -> a.showPage("settings"));
            root.addView(go, Ui.at(0, 220, 420, 80));
            firstView = go;
            return;
        }
        if (open != null) {
            buildDetail();
            return;
        }
        if (items == null && !loading && error == null) {
            load();
            return;
        }

        root.addView(Ui.text(a, "Filem & Siri", 46, Ui.WHITE, Ui.BOLD), Ui.at(6, 0, -2, -2));
        String sub = loading ? "Memuatkan…" : error != null ? "Ralat: " + error
                : (query != null ? "Carian \"" + query + "\" – " : (catName != null ? catName + " – " : "")) + (items == null ? 0 : items.size())
                + (kind.equals(Vod.MOVIE) ? " filem" : " siri");
        root.addView(Ui.text(a, sub, 24, Ui.DIM, Ui.MEDIUM), Ui.at(8, 62, 1000, -2));

        // tab
        LinearLayout tabs = new FrontLayout(a, LinearLayout.HORIZONTAL);
        tabs.addView(chip("Filem", kind.equals(Vod.MOVIE) && query == null, "tab:movie", v -> switchKind(Vod.MOVIE)), Ui.lin(-2, 60, 14));
        tabs.addView(chip("Siri", kind.equals(Vod.SERIES) && query == null, "tab:series", v -> switchKind(Vod.SERIES)), Ui.lin(-2, 60, 14));
        tabs.addView(chip("Cari…", query != null, "tab:search", v -> askSearch()), Ui.lin(-2, 60, 0));
        root.addView(tabs, Ui.at(1100, 6, -2, 60));

        // kategori
        if (cats != null && query == null) {
            HorizontalScrollView hs = new HorizontalScrollView(a);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout cr = new FrontLayout(a, LinearLayout.HORIZONTAL);
            cr.setPadding(S.px(14), S.px(12), S.px(14), S.px(12));
            Ui.clipToBounds(hs);
            hs.addView(cr);
            for (final String[] c : cats) {
                cr.addView(chip(c[1], c[0].equals(catId), "cat:" + c[0], v -> {
                    catId = c[0];
                    catName = c[1];
                    pageNo = 0;
                    lastFocusId = "cat:" + c[0];
                    load();
                }), Ui.lin(-2, 58, 12));
            }
            root.addView(hs, Ui.at(-10, 96, 1686, 84));
        }

        // grid
        final ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout col = new FrontLayout(a, LinearLayout.VERTICAL);
        col.setPadding(S.px(14), S.px(20), S.px(14), S.px(60));
        Ui.clipToBounds(sv);
        sv.addView(col);

        if (kind.equals(Vod.MOVIE) && query == null && pageNo == 0) addContinueRow(col, sv);

        if (items != null) {
            int pages = Math.max(1, (items.size() + PER_PAGE - 1) / PER_PAGE);
            if (pageNo >= pages) pageNo = 0;
            int from = pageNo * PER_PAGE, to = Math.min(items.size(), from + PER_PAGE);
            LinearLayout row = null;
            for (int k = from; k < to; k++) {
                if ((k - from) % COLS == 0) {
                    row = new FrontLayout(a, LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                    lp.bottomMargin = S.px(24);
                    col.addView(row, lp);
                }
                final LinearLayout r = row;
                Card c = poster(items.get(k));
                c.onFocus = (card, gained) -> {
                    if (gained) sv.smoothScrollTo(0, Math.max(0, r.getTop() - S.px(60)));
                };
                row.addView(c, Ui.lin(184, 276, 20));
                if (firstView == null) firstView = c;
            }
            if (pages > 1) {
                final int total = pages;
                Row more = new Row(a, "Halaman " + (pageNo + 1) + " / " + pages + "   •   Seterusnya ›", null, null);
                more.flat();
                more.setTag("more");
                more.setOnClickListener(v -> {
                    pageNo = (pageNo + 1) % total;
                    lastFocusId = null;
                    build();
                    refocus();
                });
                col.addView(more, new LinearLayout.LayoutParams(S.px(560), S.px(74)));
            }
            if (items.isEmpty()) col.addView(Ui.text(a, "Tiada kandungan dalam kategori ini.", 26, Ui.FAINT, Ui.MEDIUM));
        }
        root.addView(sv, Ui.at(-14, query == null && cats != null ? 180 : 96, 1694, query == null && cats != null ? 684 : 768));
        if (firstView == null) firstView = tabs.getChildAt(0);
    }

    private void switchKind(String k) {
        kind = k;
        catId = null;
        catName = null;
        query = null;
        pageNo = 0;
        lastFocusId = k.equals(Vod.MOVIE) ? "tab:movie" : "tab:series";
        load();
    }

    private void askSearch() {
        GlassMenu m = new GlassMenu(a, "Cari " + (kind.equals(Vod.MOVIE) ? "filem" : "siri"));
        final EditText q = m.field("Nama filem / siri", query == null ? "" : query, false);
        m.add("Cari", () -> {
            String s = q.getText().toString().trim();
            if (s.isEmpty()) return;
            query = s;
            pageNo = 0;
            lastFocusId = null;
            Toast.makeText(a, "Mencari… (kali pertama mungkin ambil masa)", Toast.LENGTH_SHORT).show();
            load();
        }).add("Batal", null).show();
    }

    private Card chip(String label, boolean on, String tag, View.OnClickListener l) {
        Card c = new Card(a, 29, on ? Ui.selected(S.px(29)) : Ui.glass(S.px(29))).flat();
        c.scaleTo = 1.1f;
        c.setTag(tag);
        TextView t = Ui.text(a, label, 22, Ui.WHITE, Ui.MEDIUM);
        t.setGravity(Gravity.CENTER);
        t.setPadding(S.px(26), 0, S.px(26), 0);
        c.addView(t, new FrameLayout.LayoutParams(-2, -1));
        c.setOnClickListener(v -> {
            lastFocusId = tag;
            l.onClick(v);
        });
        return c;
    }

    private Card poster(final Vod.Item it) {
        Card c = new Card(a, 18, Ui.solid(0xFF1A1D26, S.px(18))).flat();
        c.scaleTo = 1.09f;
        c.ambient = 0xFF3A1C7A;
        c.setTag("item:" + it.id);
        ImageView img = new ImageView(a);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Img.load(img, it.icon, S.px(200));
        c.addView(img, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(a);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xE6000000, 0x00000000}));
        c.addView(shade, new FrameLayout.LayoutParams(-1, S.px(130), Gravity.BOTTOM));
        TextView t = Ui.text(a, it.name, 19, Ui.WHITE, Ui.MEDIUM);
        t.setSingleLine(false);
        t.setMaxLines(2);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        tl.leftMargin = tl.rightMargin = tl.bottomMargin = S.px(10);
        c.addView(t, tl);
        if (!it.rating.isEmpty()) {
            TextView r = Ui.text(a, " ★ " + it.rating + " ", 16, Ui.WHITE, Ui.BOLD);
            r.setBackground(Ui.solid(0xCC000000, S.px(8)));
            FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            rl.topMargin = rl.rightMargin = S.px(8);
            c.addView(r, rl);
        }
        c.setOnClickListener(v -> openItem(it));
        return c;
    }

    /** Baris "Sambung tonton" dari sejarah tontonan. */
    private void addContinueRow(LinearLayout col, final ScrollView sv) {
        JSONArray h = Store.vodHistoryRaw(a);
        if (h.length() == 0) return;
        col.addView(Ui.text(a, "Sambung tonton", 30, Ui.WHITE, Ui.MEDIUM));
        final LinearLayout row = new FrontLayout(a, LinearLayout.HORIZONTAL);
        row.setPadding(0, S.px(16), 0, S.px(30));
        for (int i = 0; i < h.length() && i < 6; i++) {
            final JSONObject o = h.optJSONObject(i);
            if (o == null) continue;
            Card c = new Card(a, 18, Ui.solid(0xFF1A1D26, S.px(18))).flat();
            c.scaleTo = 1.07f;
            c.setTag("cont:" + i);
            ImageView img = new ImageView(a);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Img.load(img, o.optString("icon"), S.px(300));
            c.addView(img, new FrameLayout.LayoutParams(-1, -1));
            View shade = new View(a);
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xE6000000, 0x00000000}));
            c.addView(shade, new FrameLayout.LayoutParams(-1, S.px(100), Gravity.BOTTOM));
            TextView t = Ui.text(a, o.optString("title"), 20, Ui.WHITE, Ui.MEDIUM);
            FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            tl.leftMargin = tl.rightMargin = S.px(12);
            tl.bottomMargin = S.px(18);
            c.addView(t, tl);
            View track = new View(a);
            track.setBackground(Ui.solid(0x55FFFFFF, 0));
            c.addView(track, new FrameLayout.LayoutParams(-1, S.px(6), Gravity.BOTTOM));
            long dur = o.optLong("dur", 1);
            View bar = new View(a);
            bar.setBackground(Ui.solid(0xFF2E8BFF, 0));
            c.addView(bar, new FrameLayout.LayoutParams(Math.round(S.px(300) * U.clamp(o.optLong("pos") / (float) dur, 0, 1)), S.px(6), Gravity.BOTTOM));
            final String tag = "cont:" + i;
            c.setOnClickListener(v -> {
                lastFocusId = tag;
                play(new String[]{o.optString("url")}, new String[]{o.optString("title")}, 0, o.optString("icon"));
            });
            c.onFocus = (card, gained) -> {
                if (gained) sv.smoothScrollTo(0, 0);
            };
            row.addView(c, Ui.lin(300, 170, 20));
            if (firstView == null) firstView = c;
        }
        col.addView(row);
    }

    // ---------------------------------------------------------------- butiran

    private void openItem(final Vod.Item it) {
        open = it;
        detail = null;
        season = -1;
        lastFocusId = "item:" + it.id;
        build();
        final int g = ++gen;
        Vod.IO.execute(() -> {
            Vod.Detail d = null;
            String err = null;
            try {
                d = Vod.detail(a, it);
            } catch (Exception e) {
                err = e.getMessage();
            }
            final Vod.Detail fd = d;
            final String fe = err;
            Hub.main.post(() -> {
                if (g != gen || open != it) return;
                detail = fd;
                if (fd == null) Toast.makeText(a, "Gagal muat maklumat: " + fe, Toast.LENGTH_LONG).show();
                if (fd != null && !fd.seasons.isEmpty()) season = fd.seasons.keySet().iterator().next();
                if (root.isAttachedToWindow()) {
                    build();
                    if (firstView != null) firstView.requestFocus();
                }
            });
        });
    }

    private void buildDetail() {
        final Vod.Item it = open;
        Vod.Detail d = detail;
        // panel sinematik: latar (backdrop) kabur di dalam kad besar, tidak menutup sidebar/jam
        FrameLayout panel = new FrameLayout(a);
        panel.setBackground(Ui.solid(0xFF0B0E18, S.px(34)));
        panel.setClipToOutline(true);
        ImageView back = new ImageView(a);
        back.setScaleType(ImageView.ScaleType.CENTER_CROP);
        back.setAlpha(0.45f);
        Img.load(back, d != null && !d.backdrop.isEmpty() ? d.backdrop : it.icon, S.px(900));
        panel.addView(back, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(a);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xF0080A12, 0xB0080A12, 0x40080A12}));
        panel.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        root.addView(panel, Ui.at(0, 0, 1666, 864));

        FrameLayout pf = new FrameLayout(a);
        pf.setBackground(Ui.solid(0xFF1A1D26, S.px(22)));
        pf.setClipToOutline(true);
        pf.setElevation(S.px(12));
        ImageView poster = new ImageView(a);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Img.load(poster, d != null && !d.poster.isEmpty() ? d.poster : it.icon, S.px(320));
        pf.addView(poster, new FrameLayout.LayoutParams(-1, -1));
        root.addView(pf, Ui.at(40, 40, 300, 450));

        root.addView(Ui.text(a, it.name, 52, Ui.WHITE, Ui.BOLD), Ui.at(380, 36, 1250, -2));
        if (d == null) {
            root.addView(Ui.text(a, "Memuatkan maklumat…", 26, Ui.DIM, Ui.MEDIUM), Ui.at(382, 114, 1000, -2));
            Row backBtn = new Row(a, "‹  Kembali", null, null);
            backBtn.setOnClickListener(v -> onBack());
            root.addView(backBtn, Ui.at(380, 170, 300, 72));
            firstView = backBtn;
            return;
        }
        StringBuilder meta = new StringBuilder();
        for (String s : new String[]{d.year, d.genre, d.duration, d.rating.isEmpty() ? "" : "★ " + d.rating}) {
            if (s == null || s.isEmpty()) continue;
            if (meta.length() > 0) meta.append("   •   ");
            meta.append(s);
        }
        root.addView(Ui.text(a, meta.toString(), 26, Ui.DIM, Ui.MEDIUM), Ui.at(382, 112, 1250, -2));
        TextView plot = Ui.text(a, d.plot.isEmpty() ? "Tiada sinopsis." : d.plot, 24, 0xE6FFFFFF, Ui.MEDIUM);
        plot.setSingleLine(false);
        plot.setMaxLines(it.kind.equals(Vod.MOVIE) ? 6 : 3);
        plot.setLineSpacing(S.px(6), 1f);
        root.addView(plot, Ui.at(382, 158, 1240, -2));

        if (it.kind.equals(Vod.MOVIE)) {
            final String url = Vod.movieUrl(a, it, d.ext);
            LinearLayout btns = new FrontLayout(a, LinearLayout.HORIZONTAL);
            long pos = url == null ? 0 : Store.vodPos(a, url);
            Row watch = new Row(a, pos > 30_000 ? "▶   Sambung tonton" : "▶   Tonton", null, null);
            watch.setTag("watch");
            watch.setOnClickListener(v -> {
                lastFocusId = "watch";
                play(new String[]{url}, new String[]{it.name}, 0, it.icon);
            });
            btns.addView(watch, Ui.lin(380, 76, 20));
            if (pos > 30_000) {
                Row restart = new Row(a, "↺   Dari mula", null, null);
                restart.setOnClickListener(v -> {
                    Store.saveVod(a, it.name, url, it.icon, 0, 0);
                    play(new String[]{url}, new String[]{it.name}, 0, it.icon);
                });
                btns.addView(restart, Ui.lin(320, 76, 0));
            }
            root.addView(btns, Ui.at(380, 440, -2, 76));
            if (!d.cast.isEmpty()) {
                TextView cast = Ui.text(a, "Pelakon: " + d.cast, 22, Ui.FAINT, Ui.MEDIUM);
                cast.setSingleLine(false);
                cast.setMaxLines(2);
                root.addView(cast, Ui.at(382, 540, 1240, -2));
            }
            firstView = watch;
            return;
        }

        // ---- siri: musim + episod
        if (d.seasons.isEmpty()) {
            root.addView(Ui.text(a, "Tiada episod.", 26, Ui.FAINT, Ui.MEDIUM), Ui.at(382, 270, -2, -2));
            return;
        }
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout sr = new FrontLayout(a, LinearLayout.HORIZONTAL);
        sr.setPadding(S.px(10), S.px(10), S.px(10), S.px(10));
        Ui.clipToBounds(hs);
        hs.addView(sr);
        View firstSeason = null;
        for (final Integer sn : d.seasons.keySet()) {
            Card ch = chip("Musim " + sn, sn == season, "season:" + sn, v -> {
                season = sn;
                build();
                View t = root.findViewWithTag("season:" + sn);
                if (t != null) t.requestFocus();
            });
            sr.addView(ch, Ui.lin(-2, 56, 12));
            if (sn == season || firstSeason == null) firstSeason = ch;
        }
        root.addView(hs, Ui.at(370, 262, 1270, 80));

        final List<Vod.Ep> eps = d.seasons.get(season);
        final ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = new FrontLayout(a, LinearLayout.VERTICAL);
        col.setPadding(S.px(14), S.px(10), S.px(14), S.px(40));
        Ui.clipToBounds(sv);
        sv.addView(col);
        final String[] urls = new String[eps == null ? 0 : eps.size()], titles = new String[urls.length];
        for (int i = 0; i < urls.length; i++) {
            Vod.Ep e = eps.get(i);
            urls[i] = Vod.episodeUrl(a, e);
            titles[i] = it.name + " – M" + e.season + " E" + e.num + (e.title.isEmpty() ? "" : ": " + e.title);
        }
        for (int i = 0; i < urls.length; i++) {
            final int idx = i;
            Vod.Ep e = eps.get(i);
            long pos = urls[i] == null ? 0 : Store.vodPos(a, urls[i]);
            Row r = new Row(a, "E" + e.num + "   " + e.title, e.plot.isEmpty() ? null : e.plot,
                    pos > 30_000 ? "Sambung" : e.duration);
            r.flat();
            r.setTag("ep:" + i);
            r.setOnClickListener(v -> {
                lastFocusId = "ep:" + idx;
                play(urls, titles, idx, it.icon);
            });
            r.onFocus = (card, gained) -> {
                if (gained) sv.smoothScrollTo(0, Math.max(0, card.getTop() - S.px(80)));
            };
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(S.px(1220), S.px(e.plot.isEmpty() ? 76 : 96));
            lp.bottomMargin = S.px(10);
            col.addView(r, lp);
            if (i == 0) firstView = r;
        }
        root.addView(sv, Ui.at(370, 342, 1270, 510));
        if (firstView == null) firstView = firstSeason;
    }

    private void play(String[] urls, String[] titles, int index, String icon) {
        for (String u : urls) {
            if (u == null) {
                Toast.makeText(a, "Log masuk akaun IPTV dahulu", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        a.open(new Intent(a, VodPlayerActivity.class).putExtra("urls", urls).putExtra("titles", titles)
                .putExtra("index", index).putExtra("icon", icon));
    }

    @Override
    void onResume() {
        // kembali dari pemain: kemas kini "Sambung tonton" / butang sambung
        if (root.isAttachedToWindow() && Store.xtream(a) != null) {
            build();
            refocus();
        }
    }
}
