package com.aqil.launcher;

import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Pelayar saluran Live TV: kumpulan, grid logo, semak saluran mati. */
final class LivePage extends Page {
    private static final int PER_PAGE = 60;
    private String group = null;
    private int pageNo = 0;
    private boolean onlyAlive;
    private boolean loading;

    LivePage(MainActivity a) {
        super(a);
    }

    @Override
    void build() {
        root.removeAllViews();
        final List<Channel> all = Hub.channels;
        if (all.isEmpty() && !loading) {
            loading = true;
            Hub.loadChannels(false, new Hub.Done() {
                @Override
                public void run(int count, String error) {
                    loading = false;
                    if (count == 0) Toast.makeText(a, "Tiada saluran: " + error, Toast.LENGTH_LONG).show();
                    if (root.isAttachedToWindow()) {
                        build();
                        if (firstView != null) firstView.requestFocus();
                    }
                }
            });
        }
        root.addView(Ui.text(a, "Live TV", 46, Ui.WHITE, Ui.BOLD), Ui.at(6, 0, -2, -2));
        int alive = 0, dead = 0;
        for (Channel c : all) {
            if (Boolean.TRUE.equals(c.alive)) alive++;
            else if (Boolean.FALSE.equals(c.alive)) dead++;
        }
        String info = loading ? "Memuatkan senarai saluran…" : all.size() + " saluran"
                + (alive + dead > 0 ? "  •  " + alive + " berfungsi, " + dead + " mati" : "");
        root.addView(Ui.text(a, info, 24, Ui.DIM, Ui.MEDIUM), Ui.at(8, 62, -2, -2));

        // butang tindakan
        LinearLayout actions = new LinearLayout(a);
        Ui.noClip(actions);
        Row watch = new Row(a, "▶  Sambung tonton", null, null);
        watch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.openLive(-1);
            }
        });
        actions.addView(watch, Ui.lin(330, 76, 20));
        Row check = new Row(a, "Semak saluran", null, null);
        check.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(a, "Menyemak " + Hub.channels.size() + " saluran… (boleh ambil masa)", Toast.LENGTH_LONG).show();
                Hub.probe(new Runnable() {
                    @Override
                    public void run() {
                        onlyAlive = true;
                        if (root.isAttachedToWindow()) build();
                        Toast.makeText(a, "Semakan siap. Saluran mati disembunyikan.", Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
        actions.addView(check, Ui.lin(290, 76, 20));
        Row filter = new Row(a, onlyAlive ? "Tunjuk semua" : "Hanya yang berfungsi", null, null);
        filter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onlyAlive = !onlyAlive;
                pageNo = 0;
                build();
            }
        });
        actions.addView(filter, Ui.lin(340, 76, 20));
        Row reload = new Row(a, "Muat semula", null, null);
        reload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loading = true;
                build();
                Hub.loadChannels(true, new Hub.Done() {
                    @Override
                    public void run(int count, String error) {
                        loading = false;
                        Toast.makeText(a, count > 0 ? count + " saluran dimuatkan" : "Gagal: " + error, Toast.LENGTH_LONG).show();
                        if (root.isAttachedToWindow()) build();
                    }
                });
            }
        });
        actions.addView(reload, Ui.lin(260, 76, 0));
        root.addView(actions, Ui.at(0, 106, -2, 76));
        firstView = watch;

        // kumpulan
        Set<String> groups = new LinkedHashSet<>();
        for (Channel c : all) if (!c.group.isEmpty()) for (String g : c.group.split(";")) groups.add(g.trim());
        HorizontalScrollView gs = new HorizontalScrollView(a);
        gs.setHorizontalScrollBarEnabled(false);
        LinearLayout gr = new LinearLayout(a);
        gr.setPadding(S.px(10), S.px(10), S.px(10), S.px(10));
        Ui.noClip(gs, gr);
        gs.addView(gr);
        gr.addView(chip("Semua", null), Ui.lin(-2, 60, 14));
        for (String g : groups) gr.addView(chip(g, g), Ui.lin(-2, 60, 14));
        root.addView(gs, Ui.at(-10, 196, 1680, 84));

        // grid saluran
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            Channel c = all.get(i);
            if (group != null && !c.group.contains(group)) continue;
            if (onlyAlive && Boolean.FALSE.equals(c.alive)) continue;
            idx.add(i);
        }
        int pages = Math.max(1, (idx.size() + PER_PAGE - 1) / PER_PAGE);
        if (pageNo >= pages) pageNo = 0;
        final ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(S.px(14), S.px(20), S.px(14), S.px(60));
        Ui.noClip(sv, col);
        sv.addView(col);
        LinearLayout row = null;
        int from = pageNo * PER_PAGE, to = Math.min(idx.size(), from + PER_PAGE);
        for (int k = from; k < to; k++) {
            if ((k - from) % 6 == 0) {
                row = new LinearLayout(a);
                Ui.noClip(row);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.bottomMargin = S.px(26);
                col.addView(row, lp);
            }
            final LinearLayout r = row;
            Card c = channelCard(idx.get(k), all.get(idx.get(k)));
            c.onFocus = new Card.OnFocus() {
                @Override
                public void onFocus(Card card, boolean gained) {
                    if (gained) sv.smoothScrollTo(0, Math.max(0, r.getTop() - S.px(120)));
                }
            };
            row.addView(c, Ui.lin(250, 150, 22));
        }
        if (pages > 1) {
            final int total = pages;
            Row more = new Row(a, "Halaman " + (pageNo + 1) + " / " + pages + "  —  Seterusnya ›", null, null);
            more.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pageNo = (pageNo + 1) % total;
                    build();
                    if (firstView != null) firstView.requestFocus();
                }
            });
            col.addView(more, new LinearLayout.LayoutParams(S.px(600), S.px(76)));
        }
        root.addView(sv, Ui.at(0, 284, 1660, 500));
    }

    private Card chip(String label, final String g) {
        Card c = new Card(a, 30, (g == null ? group == null : g.equals(group)) ? Ui.selected(S.px(30)) : Ui.glass(S.px(30)));
        c.scaleTo = 1.08f;
        TextView t = Ui.text(a, label, 24, Ui.WHITE, Ui.MEDIUM);
        t.setGravity(Gravity.CENTER);
        t.setPadding(S.px(28), 0, S.px(28), 0);
        c.addView(t, new FrameLayout.LayoutParams(-2, -1));
        c.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                group = g;
                pageNo = 0;
                build();
            }
        });
        return c;
    }

    private Card channelCard(final int index, Channel ch) {
        Card c = new Card(a, 22);
        ImageView logo = new ImageView(a);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Img.load(logo, ch.logo, S.px(220));
        c.addView(logo, Ui.at(30, 14, 190, 80));
        TextView name = Ui.text(a, (index + 1) + "  " + ch.name, 22, Ui.WHITE, Ui.MEDIUM);
        name.setGravity(Gravity.CENTER);
        c.addView(name, Ui.at(10, 104, 230, -2));
        if (Boolean.FALSE.equals(ch.alive)) {
            TextView dead = Ui.text(a, "MATI", 16, Ui.WHITE, Ui.BOLD);
            dead.setBackground(Ui.solid(0xCCE5263B, S.px(8)));
            dead.setPadding(S.px(8), S.px(3), S.px(8), S.px(3));
            c.addView(dead, Ui.at(10, 10, -2, -2));
        }
        c.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.openLive(index);
            }
        });
        return c;
    }
}
