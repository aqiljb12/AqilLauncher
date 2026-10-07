package com.aqil.launcher;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Filem & Siri dari akaun IPTV, gaya Apple TV / Netflix:
 *  - Paparan utama: "hero" besar di atas (gambar latar, tajuk, maklumat, sinopsis) yang mengikut poster yang difokus,
 *    dan baris-baris poster yang skrol ke kanan (Sambung tonton + satu baris bagi setiap kategori, dimuat malas).
 *  - "Lihat semua" / "Semua kategori" / carian → grid poster penuh dengan cip kategori.
 *  - Butiran filem / siri (musim & episod).
 */
final class MoviesPage extends Page {
    private static final int PER_PAGE = 48, COLS = 8, MAX_ROWS = 12, ROW_ITEMS = 20;
    private static final ExecutorService HERO_IO = Executors.newSingleThreadExecutor();
    /** true = grid kategori / carian; false = paparan baris (utama). */
    private boolean grid;
    // ---- paparan baris
    private int browseGen;
    private ImageView heroBlur, heroBack;
    private TextView heroTitle, heroMeta, heroPlot, heroKind;
    private Object heroFor;
    private ScrollView rowsSv;
    private String pendingFocus;
    private View fallbackFocus;
    private final Runnable heroFetch = new Runnable() {
        @Override
        public void run() {
            fetchHeroDetail();
        }
    };
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
        if (grid) {
            grid = false;
            query = null;
            catId = null;
            catName = null;
            pageNo = 0;
            build();
            refocus();
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
        if (t != null) {
            t.requestFocus();
            return;
        }
        // baris belum dimuat: fokus sementara, kemudian pindah bila baris yang mengandungi item itu siap
        pendingFocus = lastFocusId == null ? "first" : lastFocusId;
        fallbackFocus = firstView;
        if (firstView != null) firstView.requestFocus();
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
        if (!grid) {
            buildBrowse();
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
        tabs.addView(chip("‹  Utama", false, "tab:home", v -> onBack()), Ui.lin(-2, 60, 14));
        tabs.addView(chip("Filem", kind.equals(Vod.MOVIE) && query == null, "tab:movie", v -> switchKind(Vod.MOVIE)), Ui.lin(-2, 60, 14));
        tabs.addView(chip("Siri", kind.equals(Vod.SERIES) && query == null, "tab:series", v -> switchKind(Vod.SERIES)), Ui.lin(-2, 60, 14));
        tabs.addView(chip("Cari…", query != null, "tab:search", v -> askSearch()), Ui.lin(-2, 60, 0));
        root.addView(tabs, Ui.at(900, 6, -2, 60));

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
        if (firstView == null) firstView = tabs.getChildAt(1);
    }

    private void switchKind(String k) {
        kind = k;
        catId = null;
        catName = null;
        query = null;
        pageNo = 0;
        lastFocusId = k.equals(Vod.MOVIE) ? "tab:movie" : "tab:series";
        if (grid) load();
        else {
            build();
            refocus();
        }
    }

    /** Buka grid penuh bagi satu kategori (null = kategori pertama). */
    private void openGrid(String id, String name) {
        grid = true;
        query = null;
        catId = id;
        catName = name;
        items = null; // jangan tunjuk grid kategori lama semasa memuat
        pageNo = 0;
        lastFocusId = null;
        load();
    }

    private void askSearch() {
        GlassMenu m = new GlassMenu(a, "Cari " + (kind.equals(Vod.MOVIE) ? "filem" : "siri"));
        final EditText q = m.field("Nama filem / siri", query == null ? "" : query, false);
        m.add("Cari", () -> {
            String s = q.getText().toString().trim();
            if (s.isEmpty()) return;
            query = s;
            grid = true;
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

    // ---------------------------------------------------------------- paparan utama (hero + baris)

    /** ImageView yang muncul perlahan (fade) setiap kali gambar baharu dipasang. */
    private static final class FadeImage extends ImageView {
        private final float target;

        FadeImage(Context c, float target) {
            super(c);
            this.target = target;
            setScaleType(ScaleType.CENTER_CROP);
        }

        @Override
        public void setImageBitmap(Bitmap b) {
            super.setImageBitmap(b);
            if (b == null) return;
            animate().cancel();
            setAlpha(Math.min(getAlpha(), target * 0.35f));
            animate().alpha(target).setDuration(420).start();
        }
    }

    private void buildBrowse() {
        final int g = ++browseGen;
        heroFor = null;
        // ---- hero: lebih lebar dari halaman supaya penuh ke kanan bila sidebar disorok
        FrameLayout hero = new FrameLayout(a);
        hero.setBackground(Ui.solid(0xFF0B0E18, S.px(34)));
        hero.setClipToOutline(true);
        heroBlur = new FadeImage(a, 0.6f); // poster dikecilkan ke ~36px lalu dibesarkan = latar kabur lembut
        hero.addView(heroBlur, new FrameLayout.LayoutParams(-1, -1));
        heroBack = new FadeImage(a, 0.9f); // gambar latar sebenar (backdrop) bila ada
        hero.addView(heroBack, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(a);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xF50B0E18, 0xC80B0E18, 0x400B0E18, 0x100B0E18}));
        hero.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        View fade = new View(a);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x000B0E18, 0xD00B0E18}));
        hero.addView(fade, new FrameLayout.LayoutParams(-1, S.px(150), Gravity.BOTTOM));
        heroKind = Ui.text(a, kind.equals(Vod.MOVIE) ? "FILEM" : "SIRI", 20, 0xFF64B5FF, Ui.BOLD);
        heroKind.setLetterSpacing(0.2f);
        hero.addView(heroKind, Ui.at(48, 96, -2, -2));
        heroTitle = Ui.text(a, "Memuatkan…", 54, Ui.WHITE, Ui.BOLD);
        heroTitle.setShadowLayer(12, 0, 3, 0x99000000);
        hero.addView(heroTitle, Ui.at(46, 126, 1050, -2));
        heroMeta = Ui.text(a, "", 24, 0xE6FFFFFF, Ui.MEDIUM);
        hero.addView(heroMeta, Ui.at(48, 200, 1050, -2));
        heroPlot = Ui.text(a, "", 23, 0xD9FFFFFF, Ui.MEDIUM);
        heroPlot.setSingleLine(false);
        heroPlot.setMaxLines(3);
        heroPlot.setLineSpacing(S.px(5), 1f);
        hero.addView(heroPlot, Ui.at(48, 244, 980, -2));
        root.addView(hero, Ui.at(0, 0, 1806, 404));

        // ---- tab (kanan atas hero, kekal dalam kawasan nampak)
        FrameLayout tabsBox = new FrameLayout(a);
        Ui.noClip(tabsBox);
        LinearLayout tabs = new FrontLayout(a, LinearLayout.HORIZONTAL);
        tabs.addView(chip("Filem", kind.equals(Vod.MOVIE), "tab:movie", v -> switchKind(Vod.MOVIE)), Ui.lin(-2, 58, 12));
        tabs.addView(chip("Siri", kind.equals(Vod.SERIES), "tab:series", v -> switchKind(Vod.SERIES)), Ui.lin(-2, 58, 12));
        tabs.addView(chip("Cari…", false, "tab:search", v -> askSearch()), Ui.lin(-2, 58, 12));
        tabs.addView(chip("Semua kategori", false, "tab:all", v -> openGrid(null, null)), Ui.lin(-2, 58, 0));
        tabsBox.addView(tabs, new FrameLayout.LayoutParams(-2, S.px(58), Gravity.END));
        root.addView(tabsBox, Ui.at(0, 26, 1636, 58));
        firstView = tabs.getChildAt(kind.equals(Vod.MOVIE) ? 0 : 1);

        // ---- baris
        rowsSv = new ScrollView(a);
        rowsSv.setVerticalScrollBarEnabled(false);
        rowsSv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout col = new FrontLayout(a, LinearLayout.VERTICAL);
        col.setPadding(0, S.px(4), 0, S.px(320)); // ruang bawah supaya baris terakhir boleh naik ke atas
        Ui.clipToBounds(rowsSv);
        rowsSv.addView(col);
        root.addView(rowsSv, Ui.at(-14, 410, 1834, 454));

        boolean hasCont = addContinueRow(col);
        List<String[]> cs = Vod.cachedCategories(kind);
        if (cs == null) {
            col.addView(Ui.text(a, "Memuatkan kategori…", 24, Ui.FAINT, Ui.MEDIUM), rowTitleLp());
            final String k = kind;
            Vod.IO.execute(() -> {
                String err = null;
                try {
                    Vod.categories(a, k);
                } catch (Exception e) {
                    err = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String fe = err;
                Hub.main.post(() -> {
                    if (g != browseGen || !root.isAttachedToWindow() || grid || open != null) return;
                    if (fe != null) {
                        heroTitle.setText("Tidak dapat memuatkan");
                        heroMeta.setText(fe);
                        return;
                    }
                    View f = a.getCurrentFocus();
                    String keep = f != null && f.getTag() instanceof String ? (String) f.getTag() : null;
                    build();
                    View t = keep == null ? null : root.findViewWithTag(keep);
                    if (t != null) t.requestFocus();
                    else refocus();
                });
            });
            return;
        }
        if (cs.isEmpty()) {
            heroTitle.setText(kind.equals(Vod.MOVIE) ? "Tiada filem" : "Tiada siri");
            heroMeta.setText("Akaun IPTV ini tiada kandungan " + (kind.equals(Vod.MOVIE) ? "filem." : "siri."));
        }
        if (pendingFocus == null && !hasCont) {
            pendingFocus = "first";
            fallbackFocus = firstView;
        }
        for (int i = 0; i < cs.size() && i < MAX_ROWS; i++) addCategoryRow(col, cs.get(i), g, i == 0 && !hasCont);
        if (cs.size() > MAX_ROWS) {
            Row more = new Row(a, "Semua " + cs.size() + " kategori  ›", null, null);
            more.flat();
            more.setTag("row:allcats");
            more.setOnClickListener(v -> openGrid(null, null));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(S.px(520), S.px(74));
            lp.leftMargin = S.px(14);
            lp.topMargin = S.px(10);
            col.addView(more, lp);
        }
    }

    private LinearLayout.LayoutParams rowTitleLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = S.px(18);
        lp.topMargin = S.px(8);
        return lp;
    }

    /** Satu blok baris: tajuk + jalur poster mendatar. Pulangkan jalur (untuk diisi). */
    private LinearLayout rowBlock(LinearLayout col, String title, int height) {
        LinearLayout block = new FrontLayout(a, LinearLayout.VERTICAL);
        block.addView(Ui.text(a, title, 27, Ui.WHITE, Ui.MEDIUM), rowTitleLp());
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout strip = new FrontLayout(a, LinearLayout.HORIZONTAL);
        strip.setPadding(S.px(18), S.px(16), S.px(60), S.px(20));
        Ui.clipToBounds(hs);
        hs.addView(strip);
        block.addView(hs, new LinearLayout.LayoutParams(-1, S.px(height + 36)));
        col.addView(block);
        return strip;
    }

    /** Fokus pada item dalam baris → baris naik ke atas kawasan baris & hero dikemas kini. */
    private void focusRow(View card, Object item) {
        View block = card;
        while (block != null && !(block.getParent() instanceof LinearLayout && ((View) block.getParent()).getParent() == rowsSv)) {
            block = block.getParent() instanceof View ? (View) block.getParent() : null;
        }
        if (block != null) rowsSv.smoothScrollTo(0, Math.max(0, block.getTop() - S.px(4)));
        showHero(item);
    }

    private void addCategoryRow(LinearLayout col, final String[] cat, final int g, final boolean first) {
        final LinearLayout strip = rowBlock(col, cat[1], 240);
        for (int i = 0; i < 7; i++) { // rangka sementara semasa dimuat
            View ph = new View(a);
            ph.setBackground(Ui.solid(0x22FFFFFF, S.px(18)));
            strip.addView(ph, Ui.lin(160, 240, 18));
        }
        final String k = kind;
        Vod.IO.execute(() -> {
            List<Vod.Item> l = null;
            try {
                l = Vod.items(a, k, cat[0]);
            } catch (Exception ignored) {
            }
            final List<Vod.Item> fl = l;
            Hub.main.post(() -> {
                if (g != browseGen || !root.isAttachedToWindow() || grid || open != null) return;
                strip.removeAllViews();
                if (fl == null || fl.isEmpty()) {
                    strip.addView(Ui.text(a, fl == null ? "Gagal dimuat" : "Tiada kandungan", 22, Ui.FAINT, Ui.MEDIUM));
                    return;
                }
                for (int i = 0; i < fl.size() && i < ROW_ITEMS; i++) strip.addView(rowPoster(fl.get(i)), Ui.lin(160, 240, 18));
                Card all = new Card(a, 18, Ui.glass(S.px(18))).flat();
                all.scaleTo = 1.09f;
                all.setTag("all:" + cat[0]);
                TextView t = Ui.text(a, "Lihat semua\n" + fl.size() + "  ›", 24, Ui.WHITE, Ui.MEDIUM);
                t.setSingleLine(false);
                t.setGravity(Gravity.CENTER);
                all.addView(t, new FrameLayout.LayoutParams(-1, -1));
                all.setOnClickListener(v -> openGrid(cat[0], cat[1]));
                all.onFocus = (c, gained) -> {
                    if (gained) focusRow(c, cat[1]);
                };
                strip.addView(all, Ui.lin(160, 240, 0));
                if (heroFor == null) showHero(fl.get(0));
                applyPendingFocus(strip, first);
            });
        });
    }

    /** Fokus tertangguh: pindah ke item yang dikehendaki bila barisnya siap, jika pengguna belum bergerak. */
    private void applyPendingFocus(LinearLayout strip, boolean firstRow) {
        if (pendingFocus == null) return;
        View cur = a.getCurrentFocus();
        if (cur != null && cur != fallbackFocus) {
            pendingFocus = null; // pengguna sudah bergerak sendiri
            return;
        }
        View t = pendingFocus.equals("first") ? (firstRow && strip.getChildCount() > 0 ? strip.getChildAt(0) : null)
                : strip.findViewWithTag(pendingFocus);
        if (t != null) {
            t.requestFocus();
            pendingFocus = null;
        }
    }

    private Card rowPoster(final Vod.Item it) {
        Card c = poster(it);
        c.onFocus = (card, gained) -> {
            if (gained) focusRow(card, it);
        };
        return c;
    }

    /** Baris "Sambung tonton" dari sejarah tontonan (filem & episod). */
    private boolean addContinueRow(LinearLayout col) {
        JSONArray h = Store.vodHistoryRaw(a);
        if (h.length() == 0) return false;
        LinearLayout strip = rowBlock(col, "Sambung tonton", 170);
        for (int i = 0; i < h.length() && i < 10; i++) {
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
            long dur = Math.max(1, o.optLong("dur", 1));
            View bar = new View(a);
            bar.setBackground(Ui.solid(0xFF2E8BFF, 0));
            c.addView(bar, new FrameLayout.LayoutParams(Math.round(S.px(300) * U.clamp(o.optLong("pos") / (float) dur, 0, 1)), S.px(6), Gravity.BOTTOM));
            final String tag = "cont:" + i;
            c.setOnClickListener(v -> {
                lastFocusId = tag;
                play(new String[]{o.optString("url")}, new String[]{o.optString("title")}, 0, o.optString("icon"));
            });
            c.onFocus = (card, gained) -> {
                if (gained) focusRow(card, o);
            };
            strip.addView(c, Ui.lin(300, 170, 20));
            if (i == 0) firstView = c;
        }
        return true;
    }

    // ---------------------------------------------------------------- hero

    private String heroIcon, heroBackUrl;
    /** Gambar hero dipasang selepas fokus berhenti ~0.2s – skrol laju sepanjang baris tidak berkelip. */
    private final Runnable heroImages = new Runnable() {
        @Override
        public void run() {
            if (heroBlur == null) return;
            if (heroIcon != null) Img.load(heroBlur, heroIcon, S.px(36)); // ~36px dibesarkan = latar kabur
            if (heroBackUrl != null && !heroBackUrl.isEmpty()) Img.load(heroBack, heroBackUrl, S.px(1200));
        }
    };

    private void showHero(Object o) {
        if (heroTitle == null || o == heroFor) return;
        heroFor = o;
        root.removeCallbacks(heroFetch);
        root.removeCallbacks(heroImages);
        heroIcon = null;
        heroBackUrl = null;
        if (heroBack.getDrawable() != null) heroBack.animate().alpha(0f).setDuration(200).start();
        if (o instanceof Vod.Item) {
            Vod.Item it = (Vod.Item) o;
            heroKind.setText(it.kind.equals(Vod.MOVIE) ? "FILEM" : "SIRI");
            heroTitle.setText(it.name);
            heroIcon = it.icon;
            Vod.Detail d = Vod.cachedDetail(it);
            heroText(it, d);
            heroBackUrl = !it.backdrop.isEmpty() ? it.backdrop : d != null ? d.backdrop : "";
            // filem: sinopsis & gambar latar hanya ada dalam get_vod_info → ambil bila fokus berhenti seketika
            if (d == null && (it.plot.isEmpty() || heroBackUrl.isEmpty())) root.postDelayed(heroFetch, 450);
        } else if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            heroKind.setText("SAMBUNG TONTON");
            heroTitle.setText(j.optString("title"));
            long dur = Math.max(1, j.optLong("dur", 1)), pos = j.optLong("pos");
            long left = Math.max(0, (dur - pos) / 60000);
            heroMeta.setText(Math.round(100f * pos / dur) + "% ditonton  •  baki " + left + " minit");
            heroPlot.setText("Tekan OK untuk sambung dari tempat terakhir.");
            heroIcon = j.optString("icon");
        } else if (o instanceof String) {
            heroKind.setText("KATEGORI");
            heroTitle.setText((String) o);
            heroMeta.setText("Lihat semua dalam kategori ini");
            heroPlot.setText("");
        }
        root.postDelayed(heroImages, 180);
    }

    private void heroText(Vod.Item it, Vod.Detail d) {
        StringBuilder meta = new StringBuilder();
        String year = d != null && !d.year.isEmpty() ? d.year : it.year;
        String genre = d != null && !d.genre.isEmpty() ? d.genre : it.genre;
        String rating = d != null && !d.rating.isEmpty() ? d.rating : it.rating;
        for (String s : new String[]{year, genre, d == null ? "" : d.duration, rating.isEmpty() ? "" : "★ " + rating}) {
            if (s == null || s.isEmpty()) continue;
            if (meta.length() > 0) meta.append("   •   ");
            meta.append(s);
        }
        heroMeta.setText(meta.toString());
        String plot = d != null && !d.plot.isEmpty() ? d.plot : it.plot;
        heroPlot.setText(plot);
    }

    private void fetchHeroDetail() {
        if (!(heroFor instanceof Vod.Item)) return;
        final Vod.Item it = (Vod.Item) heroFor;
        final int g = browseGen;
        HERO_IO.execute(() -> {
            Vod.Detail d = null;
            try {
                d = Vod.detail(a, it);
            } catch (Exception ignored) {
            }
            final Vod.Detail fd = d;
            Hub.main.post(() -> {
                if (fd == null || g != browseGen || heroFor != it || heroTitle == null) return;
                heroText(it, fd);
                if (it.backdrop.isEmpty() && !fd.backdrop.isEmpty()) {
                    heroBackUrl = fd.backdrop;
                    Img.load(heroBack, fd.backdrop, S.px(1200));
                }
            });
        });
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
