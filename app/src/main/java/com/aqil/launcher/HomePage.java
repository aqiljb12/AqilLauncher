package com.aqil.launcher;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Skrin utama ikut reka bentuk: banner besar, 2 kad apl, Kegemaran, Dibuka Baru-baru Ini. */
final class HomePage extends Page {
    private Hero hero;
    private Bitmap galleryPhoto;
    private String galleryPhotoName;

    HomePage(MainActivity a) {
        super(a);
    }

    @Override
    void build() {
        root.removeAllViews();

        hero = new Hero(a);
        hero.setSlides(slides());
        root.addView(hero, Ui.at(0, 0, 1000, 360));
        firstView = hero;

        // dua kad apl di kanan banner
        List<Apps.A> favs = Apps.favorites(a);
        for (int i = 0; i < 2 && i < favs.size(); i++) {
            root.addView(sideCard(favs.get(i)), Ui.at(1026 + i * 320, 150, 300, 210));
        }

        root.addView(Ui.text(a, "Aplikasi Kegemaran", 30, Ui.WHITE, Ui.MEDIUM), Ui.at(6, 384, -2, -2));
        LinearLayout favRow = new LinearLayout(a);
        Ui.noClip(favRow);
        int shown = 0;
        for (Apps.A app : favs) {
            if (shown >= 6) break;
            favRow.addView(a.appCard(app, 214, 120), Ui.lin(214, 120, 20));
            shown++;
        }
        Card add = new Card(a, 22);
        LinearLayout addBox = new LinearLayout(a);
        addBox.setOrientation(LinearLayout.VERTICAL);
        addBox.setGravity(Gravity.CENTER);
        addBox.addView(Ui.icon(a, R.drawable.ic_add, Ui.WHITE), new LinearLayout.LayoutParams(S.px(48), S.px(48)));
        TextView at = Ui.text(a, "Tambah Apl", 22, Ui.DIM, Ui.MEDIUM);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -2);
        alp.topMargin = S.px(8);
        addBox.addView(at, alp);
        add.addView(addBox, new FrameLayout.LayoutParams(-1, -1));
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.pickFavorite();
            }
        });
        favRow.addView(add, Ui.lin(214, 120, 0));
        root.addView(favRow, Ui.at(0, 424, -2, 120));

        // ---- siaran langsung (logo saluran, terus tonton)
        root.addView(Ui.text(a, "Siaran Langsung", 30, Ui.WHITE, Ui.MEDIUM), Ui.at(6, 566, -2, -2));
        LinearLayout liveRow = new LinearLayout(a);
        Ui.noClip(liveRow);
        List<Channel> chs = Hub.channels;
        int start = Math.max(0, Math.min(Store.lastChannel(a), chs.size() - 1));
        int shownCh = 0;
        for (int k = 0; k < chs.size() && shownCh < 8; k++) {
            int ci = (start + k) % chs.size();
            Channel ch = chs.get(ci);
            if (Boolean.FALSE.equals(ch.alive)) continue;
            liveRow.addView(channelCard(ci, ch), Ui.lin(180, 96, 18));
            shownCh++;
        }
        if (shownCh == 0) {
            TextView hint = Ui.text(a, "Memuatkan saluran…", 24, Ui.FAINT, Ui.MEDIUM);
            hint.setGravity(Gravity.CENTER_VERTICAL);
            liveRow.addView(hint, new LinearLayout.LayoutParams(-2, S.px(96)));
        }
        root.addView(liveRow, Ui.at(0, 604, -2, 96));

        root.addView(Ui.text(a, "Dibuka Baru-baru Ini", 30, Ui.WHITE, Ui.MEDIUM), Ui.at(6, 722, -2, -2));
        LinearLayout recRow = new LinearLayout(a);
        Ui.noClip(recRow);
        int n = 0;
        for (String[] r : Store.recents(a)) {
            if (r == null || r.length < 2) continue;
            Apps.A app = Apps.find(r[0]);
            long when = parseTime(r[1]);
            if (app == null || when <= 0) continue; // abaikan entri rosak, jangan jatuhkan Home
            recRow.addView(recentCard(app, when), Ui.lin(312, 94, 20));
            if (++n >= 5) break;
        }
        if (n == 0) {
            TextView hint = Ui.text(a, "Apl yang anda buka akan muncul di sini.", 24, Ui.FAINT, Ui.MEDIUM);
            recRow.addView(hint, new LinearLayout.LayoutParams(-2, S.px(94)));
            hint.setGravity(Gravity.CENTER_VERTICAL);
        }
        root.addView(recRow, Ui.at(0, 760, -2, 94));
    }

    private List<Hero.Slide> slides() {
        List<Hero.Slide> out = new ArrayList<>();
        Hero.Slide live = new Hero.Slide();
        live.badge = "Live TV";
        int n = Hub.channels.size();
        int last = Store.lastChannel(a);
        Channel ch = last >= 0 && last < n ? Hub.channels.get(last) : null;
        live.title = ch != null ? ch.name : "Live TV";
        live.sub = n > 0 ? "Siaran langsung • " + n + " saluran" : "Siaran langsung • IPTV / M3U";
        live.button = ch != null ? "Tonton Sekarang" : "Buka Live TV";
        live.logoUrl = ch != null ? ch.logo : null;
        live.bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF120A2E, 0xFF4A1D7A, 0xFFB3123A});
        live.action = new Runnable() {
            @Override
            public void run() {
                a.openLive(-1);
            }
        };
        out.add(live);

        List<File> imgs = Hub.images();
        if (!imgs.isEmpty()) {
            Hero.Slide g = new Hero.Slide();
            g.badge = "Galeri";
            g.badgeColor = Ui.BLUE;
            g.badgeIcon = R.drawable.ic_photo;
            g.title = "Gambar dari Telefon";
            g.sub = imgs.size() + " gambar • Tayangan slaid 3D";
            g.button = "Tayang";
            g.bg = WallpaperView.preview("grad:night");
            loadGalleryPhoto(imgs.get(0));
            g.photo = galleryPhoto;
            g.action = new Runnable() {
                @Override
                public void run() {
                    a.openGallery();
                }
            };
            out.add(g);
        }

        Hero.Slide r = new Hero.Slide();
        r.badge = "Remote";
        r.badgeColor = 0xFF8E3BFF;
        r.badgeIcon = R.drawable.ic_gamepad;
        r.title = "Kawal dengan Telefon";
        r.sub = U.remoteUrl().replace("http://", "") + "  •  PIN " + Store.pin(a);
        r.button = "Sambung";
        r.bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF1B0F3B, 0xFF6A1B9A, 0xFFE0218A});
        r.action = new Runnable() {
            @Override
            public void run() {
                a.showPage("remote");
            }
        };
        out.add(r);
        return out;
    }

    /** Muat gambar terbaru galeri untuk banner (sekali, di latar; bina semula bila siap). */
    private void loadGalleryPhoto(final File f) {
        if (f.getName().equals(galleryPhotoName)) return;
        galleryPhotoName = f.getName();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap b = ImageViewerActivity.decode(f, S.px(1000), S.px(380));
                Hub.main.post(new Runnable() {
                    @Override
                    public void run() {
                        galleryPhoto = b;
                        if (hero != null && hero.isAttachedToWindow()) hero.setSlides(slides());
                    }
                });
            }
        }).start();
    }

    private static String describe(Apps.A app) {
        String l = app.label.toLowerCase(Locale.ROOT);
        if (l.contains("youtube")) return "Tonton video kegemaran";
        if (l.contains("netflix")) return "Filem, siri dan lagi";
        if (l.contains("disney")) return "Disney, Marvel, Pixar";
        if (l.contains("prime")) return "Filem & rancangan Prime";
        if (l.contains("astro")) return "Siaran & ulang tayang";
        if (l.contains("spotify")) return "Muzik & podcast";
        return "Buka aplikasi";
    }

    private Card sideCard(final Apps.A app) {
        int col = app.color;
        Card c = new Card(a, 26, Ui.grad(GradientDrawable.Orientation.TL_BR, S.px(26),
                (col & 0xFFFFFF) | 0xE6000000, darker(col) | 0xE6000000, 0xE6101218));
        ImageView ic = Ui.image(a, app.icon);
        c.addView(ic, Ui.at(26, 26, 76, 76));
        c.addView(Ui.text(a, app.label, 36, Ui.WHITE, Ui.BOLD), Ui.at(26, 120, 250, -2));
        TextView d = Ui.text(a, describe(app), 22, 0xD9FFFFFF, Ui.MEDIUM);
        c.addView(d, Ui.at(26, 168, 190, -2));
        ImageView arrow = Ui.icon(a, R.drawable.ic_chevron, Ui.WHITE);
        arrow.setBackground(Ui.solid(0x33FFFFFF, S.px(30)));
        arrow.setPadding(S.px(12), S.px(12), S.px(12), S.px(12));
        c.addView(arrow, Ui.at(226, 146, 56, 56));
        a.bindApp(c, app);
        return c;
    }

    private Card recentCard(Apps.A app, long t) {
        Card c = new Card(a, 22);
        ImageView ic = Ui.image(a, app.icon);
        c.addView(ic, Ui.at(16, 14, 66, 66));
        c.addView(Ui.text(a, app.label, 27, Ui.WHITE, Ui.MEDIUM), Ui.at(98, 18, 200, -2));
        c.addView(Ui.text(a, Ui.ago(t), 21, Ui.DIM, Ui.MEDIUM), Ui.at(98, 56, 200, -2));
        a.bindApp(c, app);
        return c;
    }

    private Card channelCard(final int index, Channel ch) {
        Card c = new Card(a, 20);
        c.ambient = 0xFF3A1C7A;
        c.scaleTo = 1.1f;
        ImageView logo = new ImageView(a);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Img.load(logo, ch.logo, S.px(160));
        c.addView(logo, Ui.at(18, 10, 144, 54));
        TextView n = Ui.text(a, ch.name, 18, Ui.DIM, Ui.MEDIUM);
        n.setGravity(Gravity.CENTER);
        c.addView(n, Ui.at(6, 68, 168, -2));
        c.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.openLive(index);
            }
        });
        return c;
    }

    private static long parseTime(String s) {
        try {
            return s == null ? -1 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static int darker(int c) {
        return Color.rgb((int) (Color.red(c) * 0.45f), (int) (Color.green(c) * 0.45f), (int) (Color.blue(c) * 0.45f)) & 0xFFFFFF;
    }
}
