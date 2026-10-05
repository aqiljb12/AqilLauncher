package com.aqil.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Map;

/**
 * Launcher utama. Susun atur 1920x1080 (diskala ikut TV):
 * jam & cuaca di atas, butang bulat kanan atas, sidebar kiri dengan penunjuk meluncur, halaman di kanan.
 */
public class MainActivity extends BaseActivity {
    private WallpaperView wall;
    private FrameLayout host;
    private final Map<String, Page> pages = new HashMap<>();
    private final Map<String, NavItem[]> navs = new HashMap<>();
    private String current;
    private TextView temp, place;
    private View ambientView, indicator;
    private int ambientColor;
    private final String[] order = {"home", "apps", "live", "movies", "remote", "settings"};
    private Weather.Icon wicon;
    private boolean receiverOn, dirty;

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Apps.load(MainActivity.this, new Runnable() {
                @Override
                public void run() {
                    refreshCurrent();
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);
        Ui.noClip(root);
        root.setBackgroundColor(0xFF05070F);

        wall = new WallpaperView(this);
        root.addView(wall, new FrameLayout.LayoutParams(-1, -1));
        ambientView = new View(this);
        root.addView(ambientView, new FrameLayout.LayoutParams(-1, -1));
        scrim(root, GradientDrawable.Orientation.TOP_BOTTOM, 0, 0, -1, 260, 0xA6000000, 0x00000000);
        scrim(root, GradientDrawable.Orientation.LEFT_RIGHT, 0, 0, 1100, -1, 0x99000000, 0x00000000);
        scrim(root, GradientDrawable.Orientation.TOP_BOTTOM, 0, 520, -1, 560, 0x00000000, 0xD0000000);

        // ---- jam + cuaca
        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("h:mm");
        clock.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, S.px(84));
        clock.setTextColor(Ui.WHITE);
        clock.setTypeface(Ui.MEDIUM);
        clock.setIncludeFontPadding(false);
        root.addView(clock, Ui.at(200, 36, -2, -2));
        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEE, d MMM yyyy");
        date.setFormat12Hour("EEE, d MMM yyyy");
        date.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, S.px(28));
        date.setTextColor(0xE6FFFFFF);
        date.setTypeface(Ui.MEDIUM);
        root.addView(date, Ui.at(204, 132, -2, -2));
        View div = new View(this);
        div.setBackgroundColor(0x55FFFFFF);
        root.addView(div, Ui.at(470, 50, 2, 104));
        wicon = new Weather.Icon(this);
        root.addView(wicon, Ui.at(500, 50, 92, 92));
        temp = Ui.text(this, Weather.tempText(), 46, Ui.WHITE, Ui.MEDIUM);
        root.addView(temp, Ui.at(606, 52, -2, -2));
        place = Ui.text(this, "", 26, 0xE6FFFFFF, Ui.MEDIUM);
        root.addView(place, Ui.at(608, 110, 420, -2));

        // ---- butang bulat kanan atas
        int[][] tops = {{R.drawable.ic_search, 0}, {R.drawable.ic_settings, 1}, {R.drawable.ic_wifi, 2}, {R.drawable.ic_person, 3}};
        for (int i = 0; i < tops.length; i++) {
            final int which = tops[i][1];
            Card c = new Card(this, 40, Ui.glass(S.px(40)));
            c.scaleTo = 1.15f;
            ImageView ic = Ui.icon(this, tops[i][0], Ui.WHITE);
            c.addView(ic, new FrameLayout.LayoutParams(S.px(38), S.px(38), Gravity.CENTER));
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    topAction(which);
                }
            });
            root.addView(c, Ui.at(1484 + i * 104, 50, 80, 80));
        }

        // ---- sidebar kiri (satu-satunya navigasi) dengan penunjuk yang meluncur
        FrameLayout sideBox = new FrameLayout(this);
        sideBox.setBackground(Ui.glass(S.px(44)));
        Ui.noClip(sideBox);
        indicator = new View(this);
        indicator.setBackground(Ui.selected(S.px(30)));
        sideBox.addView(indicator, Ui.at(10, 12, 116, 112));
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(S.px(10), S.px(12), S.px(10), S.px(12));
        Ui.noClip(side);
        sideBox.addView(side, new FrameLayout.LayoutParams(-1, -2));
        root.addView(sideBox, Ui.at(40, 214, 136, 6 * 118 + 18));

        // ---- halaman
        host = new FrameLayout(this);
        Ui.noClip(host);
        root.addView(host, Ui.at(214, 186, 1666, 864));

        String[] labels = {"Home", "Apl", "Live TV", "Filem", "Remote", "Tetapan"};
        int[] icons = {R.drawable.ic_home, R.drawable.ic_apps, R.drawable.ic_livetv, R.drawable.ic_movie, R.drawable.ic_gamepad, R.drawable.ic_settings};
        for (int i = 0; i < order.length; i++) {
            final NavItem n = new NavItem(this, order[i], icons[i], labels[i], 30);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(S.px(116), S.px(112));
            lp.bottomMargin = S.px(6);
            side.addView(n, lp);
            navs.put(order[i], new NavItem[]{n});
            n.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (n.page.equals("live") && "live".equals(current)) openLive(-1);
                    else showPage(n.page);
                    Page p = pages.get(current);
                    if (p != null && p.first() != null) p.first().requestFocus();
                }
            });
        }
        setContentView(root);

        reloadWallpaper();
        showPage("home");
        final String crash = App.takeCrash();
        if (crash != null) {
            root.postDelayed(new Runnable() {
                @Override
                public void run() {
                    String[] lines = crash.split("\n");
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < lines.length && i < 7; i++) sb.append(lines[i].trim()).append('\n');
                    new GlassMenu(MainActivity.this, "Aqil Launcher terhenti sebelum ini")
                            .note(sb.toString(), 18, Ui.DIM)
                            .note("Laporan penuh: telefon › Lagi › Laporan ralat", 22, Ui.WHITE)
                            .add("OK", null)
                            .show();
                }
            }, 1200);
        }
        Apps.load(this, new Runnable() {
            @Override
            public void run() {
                refreshCurrent();
                Page p = pages.get(current);
                if (p != null && p.first() != null) p.first().requestFocus();
            }
        });
        Hub.loadChannels(false, new Hub.Done() {
            @Override
            public void run(int count, String error) {
                if ("home".equals(current) || "live".equals(current)) refreshCurrent();
            }
        });
    }

    private void scrim(FrameLayout root, GradientDrawable.Orientation o, float x, float y, float w, float h, int a, int b) {
        View v = new View(this);
        v.setBackground(new GradientDrawable(o, new int[]{a, b}));
        root.addView(v, Ui.at(x, y, w, h));
    }

    @Override
    protected void onResume() {
        super.onResume();
        wall.resume();
        if (!receiverOn) {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_PACKAGE_ADDED);
            f.addAction(Intent.ACTION_PACKAGE_REMOVED);
            f.addDataScheme("package");
            registerReceiver(pkgReceiver, f);
            receiverOn = true;
        }
        if (dirty) refreshCurrent();
        dirty = true;
        Page cp = current == null ? null : pages.get(current);
        if (cp != null) cp.onResume();
        updateWeather();
        Weather.refresh(this, false, new Runnable() {
            @Override
            public void run() {
                updateWeather();
            }
        });
    }

    @Override
    protected void onPause() {
        wall.pause();
        Page p = current == null ? null : pages.get(current);
        if (p != null) p.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (receiverOn) unregisterReceiver(pkgReceiver);
        super.onDestroy();
    }

    void updateWeather() {
        temp.setText(Weather.tempText());
        place.setText(Weather.place);
        wicon.invalidate();
    }

    void reloadWallpaper() {
        wall.reset();
        wall.apply(Store.wallpaper(this));
        if (Store.fx(this)) wall.resume();
        else wall.pause();
    }

    @Override
    void parallax(float nx, float ny) {
        wall.parallax(nx, ny);
    }

    /** Latar berubah warna lembut mengikut apl/saluran yang difokus. */
    @Override
    void ambient(int color) {
        if (!Store.fx(this)) return;
        int target = color == 0 ? 0 : (color & 0xFFFFFF) | 0x5A000000;
        if (target == ambientColor) return;
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofArgb(ambientColor, target);
        ambientColor = target;
        va.setDuration(700);
        va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(android.animation.ValueAnimator a) {
                ambientView.setBackgroundColor((Integer) a.getAnimatedValue());
            }
        });
        va.start();
    }

    // ---------------------------------------------------------------- halaman

    private Page page(String name) {
        Page p = pages.get(name);
        if (p == null) {
            switch (name) {
                case "apps": p = new AppsPage(this); break;
                case "live": p = new LivePage(this); break;
                case "movies": p = new MoviesPage(this); break;
                case "remote": p = new RemotePage(this); break;
                case "settings": p = new SettingsPage(this); break;
                default: p = new HomePage(this); break;
            }
            pages.put(name, p);
        }
        return p;
    }

    void showPage(String name) {
        Page old = current == null ? null : pages.get(current);
        if (old != null && !name.equals(current)) {
            old.onPause();
            old.onHide();
        }
        current = name;
        Page p = page(name);
        p.build();
        host.removeAllViews();
        host.addView(p.root, new FrameLayout.LayoutParams(-1, -1));
        p.root.setAlpha(0f);
        p.root.setTranslationY(S.px(40));
        p.root.setRotationX(Store.fx(this) ? 6f : 0f);
        p.root.animate().alpha(1f).translationY(0).rotationX(0).setDuration(360).setInterpolator(new DecelerateInterpolator()).start();
        for (Map.Entry<String, NavItem[]> e : navs.entrySet()) {
            for (NavItem n : e.getValue()) n.setActive(e.getKey().equals(name));
        }
        for (int i = 0; i < order.length; i++) {
            if (order[i].equals(name)) {
                indicator.animate().translationY(S.px(118) * i).setDuration(420)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f)).start();
            }
        }
        ambient(0);
    }

    /** Bina semula halaman semasa sambil kekalkan fokus jika boleh. */
    void refreshCurrent() {
        if (current == null) return;
        View f = getCurrentFocus();
        boolean inPage = f != null && isDescendant(f, host);
        Page p = page(current);
        p.build();
        if (host.getChildCount() == 0 || host.getChildAt(0) != p.root) {
            host.removeAllViews();
            host.addView(p.root, new FrameLayout.LayoutParams(-1, -1));
        }
        if ((inPage || getCurrentFocus() == null) && p.first() != null) p.first().requestFocus();
    }

    private static boolean isDescendant(View v, View parent) {
        while (v != null) {
            if (v == parent) return true;
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return false;
    }

    void pickFavorite() {
        ((AppsPage) page("apps")).pickMode = true;
        showPage("apps");
        Page p = pages.get("apps");
        if (p.first() != null) p.first().requestFocus();
        ((AppsPage) p).pickMode = true;
    }

    private void topAction(int which) {
        switch (which) {
            case 0:
                try {
                    startActivity(new Intent("android.search.action.GLOBAL_SEARCH").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception e) {
                    showPage("apps");
                }
                break;
            case 1: showPage("settings"); break;
            case 2: openSafe(new Intent(Settings.ACTION_WIFI_SETTINGS)); break;
            default: showPage("remote"); break;
        }
    }

    // ---------------------------------------------------------------- apl

    /** Kad apl: banner TV jika ada, jika tidak ikon atas latar warna apl. */
    Card appCard(final Apps.A app, float w, float h) {
        Card c;
        if (app.banner != null) {
            c = new Card(this, 20, Ui.solid(0xFF1A1D26, S.px(20)));
            ImageView b = new ImageView(this);
            b.setScaleType(ImageView.ScaleType.FIT_XY);
            b.setImageDrawable(app.banner);
            c.addView(b, new FrameLayout.LayoutParams(-1, -1));
        } else {
            int col = app.color;
            c = new Card(this, 20, Ui.grad(GradientDrawable.Orientation.TL_BR, S.px(20),
                    (col & 0xFFFFFF) | 0xF0000000, HomePage.darker(col) | 0xF0000000));
            ImageView ic = Ui.image(this, app.icon);
            float is = Math.min(h * 0.46f, 72);
            c.addView(ic, new FrameLayout.LayoutParams(S.px(is), S.px(is), Gravity.CENTER_HORIZONTAL | Gravity.TOP));
            ((FrameLayout.LayoutParams) ic.getLayoutParams()).topMargin = S.px(h * 0.14f);
            TextView t = Ui.text(this, app.label, 22, Ui.WHITE, Ui.MEDIUM);
            t.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            tl.bottomMargin = S.px(h * 0.1f);
            tl.leftMargin = tl.rightMargin = S.px(10);
            c.addView(t, tl);
        }
        bindApp(c, app);
        return c;
    }

    void bindApp(Card c, final Apps.A app) {
        c.ambient = app.color | 0xFF000000;
        c.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launch(app);
            }
        });
        c.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                appMenu(app);
                return true;
            }
        });
    }

    void launch(Apps.A app) {
        Store.recordLaunch(this, app.pkg);
        if (open(app.launch)) dirty = true;
    }

    boolean open(Intent i) {
        try {
            // Aktiviti dalaman (Live TV, Galeri) MESTI dibuka tanpa NEW_TASK|RESET_TASK: jika tidak, Android
            // boleh "membuang" permintaan itu kerana task launcher sudah di depan (punca "tekan tak keluar apa-apa").
            boolean internal = i.getComponent() != null && getPackageName().equals(i.getComponent().getPackageName());
            if (!internal) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
            overridePendingTransition(R.anim.launch_in, R.anim.launch_out);
            return true;
        } catch (Exception e) {
            Toast.makeText(this, "Tak dapat dibuka", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    void openSafe(Intent i) {
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Tak disokong pada peranti ini", Toast.LENGTH_SHORT).show();
        }
    }

    void openLive(int index) {
        Intent i = new Intent(this, LiveTvActivity.class);
        if (index >= 0) i.putExtra("index", index);
        open(i);
    }

    void openGallery() {
        if (Hub.images().isEmpty()) {
            Toast.makeText(this, "Belum ada gambar. Hantar dari telefon (Remote › Gambar).", Toast.LENGTH_LONG).show();
        } else {
            open(new Intent(this, ImageViewerActivity.class).putExtra("slideshow", true));
        }
    }

    private void appMenu(final Apps.A app) {
        final boolean fav = Store.isFavorite(this, app.pkg);
        new GlassMenu(this, app.label)
                .add("Buka", new Runnable() {
                    @Override
                    public void run() {
                        launch(app);
                    }
                })
                .add(fav ? "Buang dari Kegemaran" : "Tambah ke Kegemaran", new Runnable() {
                    @Override
                    public void run() {
                        Store.toggleFavorite(MainActivity.this, app.pkg);
                        refreshCurrent();
                    }
                })
                .add("Maklumat Apl", new Runnable() {
                    @Override
                    public void run() {
                        openSafe(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.pkg)));
                    }
                })
                .add("Nyahpasang", new Runnable() {
                    @Override
                    public void run() {
                        openSafe(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.pkg)));
                    }
                })
                .show();
    }

    // ---------------------------------------------------------------- kekunci

    @Override
    public void onNewIntent(Intent i) {
        super.onNewIntent(i);
        // butang Home ditekan semasa dalam launcher
        if (!"home".equals(current)) showPage("home");
        Page p = pages.get("home");
        if (p != null && p.first() != null) p.first().requestFocus();
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            if (f != null) f.performLongClick();
            return true;
        }
        return super.onKeyUp(keyCode, e);
    }

    @Override
    public void onBackPressed() {
        Page cur = current == null ? null : pages.get(current);
        if (cur != null && cur.onBack()) return; // cth. tutup butiran filem dahulu
        if (!"home".equals(current)) {
            showPage("home");
        }
        Page p = pages.get("home");
        if (p != null && p.first() != null) p.first().requestFocus();
    }
}
