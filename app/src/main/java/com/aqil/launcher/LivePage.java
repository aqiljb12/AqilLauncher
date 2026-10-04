package com.aqil.launcher;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pelayar Live TV: kiri = PRATONTON langsung saluran yang difokus (main sendiri selepas ~1 saat),
 * kanan = kumpulan + grid saluran. OK = tonton skrin penuh.
 */
@OptIn(markerClass = UnstableApi.class)
final class LivePage extends Page {
    private static final int PER_PAGE = 48;
    private String group;
    private int pageNo;
    private boolean onlyAlive, loading;

    private ExoPlayer preview;
    private TextureView tex;
    private ImageView pLogo;
    private TextView pName, pGroup, pState;
    private ProgressBar pSpin;
    private int focused = -1, previewing = -1;
    private final Runnable startPreview = new Runnable() {
        @Override
        public void run() {
            preview(focused);
        }
    };

    LivePage(MainActivity a) {
        super(a);
    }

    @Override
    void build() {
        releasePreview();
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

        // ================= kiri: pratonton
        root.addView(Ui.text(a, "Live TV", 46, Ui.WHITE, Ui.BOLD), Ui.at(6, 0, -2, -2));
        int alive = 0, dead = 0;
        for (Channel c : all) {
            if (Boolean.TRUE.equals(c.alive)) alive++;
            else if (Boolean.FALSE.equals(c.alive)) dead++;
        }
        root.addView(Ui.text(a, loading ? "Memuatkan senarai…" : all.size() + " saluran"
                + (alive + dead > 0 ? "  •  " + alive + " OK  •  " + dead + " mati" : ""), 24, Ui.DIM, Ui.MEDIUM), Ui.at(8, 62, 540, -2));

        FrameLayout pv = new FrameLayout(a);
        pv.setBackground(Ui.grad(GradientDrawable.Orientation.TL_BR, S.px(28), 0xFF120A2E, 0xFF2A1450, 0xFF4A0F2A));
        pv.setClipToOutline(true);
        pv.setElevation(S.px(14));
        pLogo = new ImageView(a);
        pLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        pv.addView(pLogo, new FrameLayout.LayoutParams(S.px(280), S.px(160), Gravity.CENTER));
        tex = new TextureView(a);
        tex.setAlpha(0f);
        pv.addView(tex, new FrameLayout.LayoutParams(-1, -1));
        pSpin = new ProgressBar(a);
        pSpin.setVisibility(View.GONE);
        pv.addView(pSpin, new FrameLayout.LayoutParams(S.px(70), S.px(70), Gravity.CENTER));
        TextView live = Ui.text(a, "  LANGSUNG  ", 18, Ui.WHITE, Ui.BOLD);
        live.setBackground(Ui.solid(Ui.RED, S.px(8)));
        live.setPadding(0, S.px(5), 0, S.px(5));
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        llp.leftMargin = llp.topMargin = S.px(16);
        pv.addView(live, llp);
        root.addView(pv, Ui.at(0, 110, 560, 315));

        pName = Ui.text(a, "Pilih saluran", 36, Ui.WHITE, Ui.BOLD);
        root.addView(pName, Ui.at(4, 446, 556, -2));
        pGroup = Ui.text(a, "", 24, Ui.DIM, Ui.MEDIUM);
        root.addView(pGroup, Ui.at(6, 494, 556, -2));
        pState = Ui.text(a, "Fokus pada saluran untuk pratonton  •  OK = skrin penuh", 20, Ui.FAINT, Ui.MEDIUM);
        root.addView(pState, Ui.at(6, 532, 556, -2));

        LinearLayout acts = new LinearLayout(a);
        acts.setOrientation(LinearLayout.VERTICAL);
        Ui.noClip(acts);
        Row watch = action(acts, "▶   Tonton saluran terakhir", new Runnable() {
            @Override
            public void run() {
                a.openLive(-1);
            }
        });
        if (Store.xtream(a) != null) {
            action(acts, "Akaun IPTV: " + (Xtream.status.isEmpty() ? "lihat" : Xtream.status + " • tamat " + Xtream.expiry), new Runnable() {
                @Override
                public void run() {
                    new GlassMenu(a, "Akaun IPTV (Xtream Codes)")
                            .note(Xtream.summary(), 24, Ui.WHITE)
                            .note("Tukar / log keluar akaun: Tetapan › Live TV, atau dari telefon (Lagi › Akaun IPTV).", 20, Ui.DIM)
                            .add("OK", null)
                            .show();
                }
            });
        } else action(acts, "Semak saluran mati", new Runnable() {
            @Override
            public void run() {
                Toast.makeText(a, "Menyemak " + Hub.channels.size() + " saluran…", Toast.LENGTH_LONG).show();
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
        action(acts, onlyAlive ? "Tunjuk semua saluran" : "Hanya saluran berfungsi", new Runnable() {
            @Override
            public void run() {
                onlyAlive = !onlyAlive;
                pageNo = 0;
                build();
            }
        });
        action(acts, "Muat semula senarai", new Runnable() {
            @Override
            public void run() {
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
        root.addView(acts, Ui.at(0, 578, 560, -2));

        // ================= kanan: kumpulan + grid
        Set<String> groups = new LinkedHashSet<>();
        for (Channel c : all) if (!c.group.isEmpty()) for (String g : c.group.split(";")) groups.add(g.trim());
        HorizontalScrollView gs = new HorizontalScrollView(a);
        gs.setHorizontalScrollBarEnabled(false);
        LinearLayout gr = new FrontLayout(a, LinearLayout.HORIZONTAL);
        gr.setPadding(S.px(14), S.px(12), S.px(14), S.px(12));
        Ui.noClip(gr);
        Ui.clipToBounds(gs);
        gs.addView(gr);
        gr.addView(chip("Semua", null), Ui.lin(-2, 58, 12));
        for (String g : groups) gr.addView(chip(g, g), Ui.lin(-2, 58, 12));
        root.addView(gs, Ui.at(586, 0, 1080, 84));

        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            Channel c = all.get(i);
            if (group != null && !c.group.contains(group)) continue;
            if (onlyAlive && Boolean.FALSE.equals(c.alive)) continue;
            idx.add(i);
        }
        final int pages = Math.max(1, (idx.size() + PER_PAGE - 1) / PER_PAGE);
        if (pageNo >= pages) pageNo = 0;
        final ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout col = new FrontLayout(a, LinearLayout.VERTICAL);
        col.setPadding(S.px(22), S.px(22), S.px(22), S.px(60));
        Ui.noClip(col);
        Ui.clipToBounds(sv);
        sv.addView(col);
        LinearLayout row = null;
        Card firstCard = null;
        int from = pageNo * PER_PAGE, to = Math.min(idx.size(), from + PER_PAGE);
        for (int k = from; k < to; k++) {
            if ((k - from) % 4 == 0) {
                row = new FrontLayout(a, LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.bottomMargin = S.px(24);
                col.addView(row, lp);
            }
            final LinearLayout r = row;
            Card c = channelCard(idx.get(k), all.get(idx.get(k)));
            final Card.OnFocus prev = c.onFocus;
            c.onFocus = new Card.OnFocus() {
                @Override
                public void onFocus(Card card, boolean gained) {
                    if (prev != null) prev.onFocus(card, gained);
                    if (gained) sv.smoothScrollTo(0, Math.max(0, r.getTop() - S.px(120)));
                }
            };
            row.addView(c, Ui.lin(240, 136, 24));
            if (firstCard == null || idx.get(k) == focused) firstCard = c;
        }
        if (pages > 1) {
            Row more = new Row(a, "Halaman " + (pageNo + 1) + " / " + pages + "   •   Seterusnya ›", null, null);
            more.flat();
            more.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pageNo = (pageNo + 1) % pages;
                    build();
                    if (firstView != null) firstView.requestFocus();
                }
            });
            col.addView(more, new LinearLayout.LayoutParams(S.px(560), S.px(74)));
        }
        root.addView(sv, Ui.at(586, 84, 1080, 780));
        firstView = firstCard != null ? firstCard : watch;
    }

    private Row action(LinearLayout parent, String label, final Runnable r) {
        Row row = new Row(a, label, null, null);
        row.scaleTo = 1.04f;
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, S.px(64));
        lp.bottomMargin = S.px(12);
        parent.addView(row, lp);
        return row;
    }

    private Card chip(String label, final String g) {
        boolean on = g == null ? group == null : g.equals(group);
        Card c = new Card(a, 29, on ? Ui.selected(S.px(29)) : Ui.glass(S.px(29))).flat();
        c.scaleTo = 1.1f;
        TextView t = Ui.text(a, label, 22, Ui.WHITE, Ui.MEDIUM);
        t.setGravity(Gravity.CENTER);
        t.setPadding(S.px(26), 0, S.px(26), 0);
        c.addView(t, new FrameLayout.LayoutParams(-2, -1));
        c.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                group = g;
                pageNo = 0;
                build();
                if (firstView != null) firstView.requestFocus();
            }
        });
        return c;
    }

    private Card channelCard(final int index, final Channel ch) {
        Card c = new Card(a, 22).flat();
        c.ambient = 0xFF3A1C7A;
        ImageView logo = new ImageView(a);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Img.load(logo, ch.logo, S.px(200));
        c.addView(logo, Ui.at(30, 12, 180, 76));
        TextView name = Ui.text(a, (index + 1) + "  " + ch.name, 20, Ui.WHITE, Ui.MEDIUM);
        name.setGravity(Gravity.CENTER);
        c.addView(name, Ui.at(8, 98, 224, -2));
        if (Boolean.FALSE.equals(ch.alive)) {
            c.setAlpha(0.55f);
            TextView dead = Ui.text(a, "MATI", 15, Ui.WHITE, Ui.BOLD);
            dead.setBackground(Ui.solid(0xDDE5263B, S.px(8)));
            dead.setPadding(S.px(8), S.px(3), S.px(8), S.px(3));
            c.addView(dead, Ui.at(8, 8, -2, -2));
        }
        c.onFocus = new Card.OnFocus() {
            @Override
            public void onFocus(Card card, boolean gained) {
                if (!gained) return;
                focused = index;
                pName.setText((index + 1) + "  " + ch.name);
                pGroup.setText(ch.group.isEmpty() ? "Siaran langsung" : ch.group);
                Img.load(pLogo, ch.logo, S.px(280));
                root.removeCallbacks(startPreview);
                if (previewing != index) {
                    stopPreview();
                    pState.setText("Pratonton bermula sebentar lagi…  •  OK = skrin penuh");
                    root.postDelayed(startPreview, 900);
                }
            }
        };
        c.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                releasePreview();
                a.openLive(index);
            }
        });
        return c;
    }

    /** EPG ringkas (rancangan sekarang) untuk akaun Xtream. */
    private void epg(final Channel ch) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String s = Xtream.nowNext(a, ch);
                if (s == null) return;
                Hub.main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (pGroup != null && focused >= 0 && focused < Hub.channels.size() && Hub.channels.get(focused) == ch) pGroup.setText(s);
                    }
                });
            }
        }).start();
    }

    // ---------------------------------------------------------------- pratonton

    private void preview(int index) {
        if (index < 0 || index >= Hub.channels.size() || !root.isAttachedToWindow()) return;
        if (preview == null) {
            preview = Streams.player(a, true);
            preview.setVolume(0f);
            preview.setVideoTextureView(tex);
            preview.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    pSpin.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                    if (state == Player.STATE_READY) {
                        tex.animate().alpha(1f).setDuration(350).start();
                        pState.setText("Pratonton (tanpa bunyi)  •  OK = skrin penuh");
                        if (previewing >= 0 && previewing < Hub.channels.size()) Hub.channels.get(previewing).alive = true;
                    }
                }

                @Override
                public void onPlayerError(PlaybackException e) {
                    pSpin.setVisibility(View.GONE);
                    tex.setAlpha(0f);
                    pState.setText("Saluran ini tidak tersedia sekarang (" + e.getErrorCodeName() + ")");
                }
            });
        }
        previewing = index;
        Channel pc = Hub.channels.get(index);
        if (pc.xtId != 0) epg(pc); // EPG hanya bila fokus berhenti (bukan setiap kali fokus bergerak)
        tex.setAlpha(0f);
        try {
            preview.setMediaSource(Streams.source(a, Hub.channels.get(index), null));
            preview.prepare();
            preview.play();
        } catch (Exception e) {
            pState.setText("Pratonton gagal: " + e.getMessage());
        }
    }

    private void stopPreview() {
        previewing = -1;
        if (tex != null) tex.setAlpha(0f);
        if (preview != null) preview.stop();
        if (pSpin != null) pSpin.setVisibility(View.GONE);
    }

    private void releasePreview() {
        root.removeCallbacks(startPreview);
        previewing = -1;
        if (preview != null) {
            preview.release();
            preview = null;
        }
    }

    @Override
    void onPause() {
        releasePreview();
    }

    @Override
    void onResume() {
        if (focused >= 0 && root.isAttachedToWindow()) root.postDelayed(startPreview, 900);
    }

    @Override
    void onHide() {
        releasePreview();
    }
}
