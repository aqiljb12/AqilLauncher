package com.aqil.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends BaseActivity implements Rail.Host {
    private static final class AppInfo {
        String pkg, label;
        Drawable icon;
        Intent launch;
    }

    private BackgroundView bg;
    private ScrollView scroll;
    private LinearLayout content;
    private TextView chip;
    private float d;
    private List<AppInfo> apps = new ArrayList<>();
    private String appsSig = "";
    private Tile firstTile;
    private boolean pkgReceiverRegistered;

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            loadApps();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = U.dp(this, 1);
        FrameLayout root = new FrameLayout(this);
        bg = new BackgroundView(this);
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));

        scroll = new ScrollView(this) {
            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) {
                return 0; // kita skrol sendiri ikut baris
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setPadding((int) (56 * d), (int) (36 * d), (int) (56 * d), (int) (120 * d));
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        bg.setAnimated(Store.fx(this));
        reloadWallpaper();
        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        bg.setAnimated(Store.fx(this));
        if (!pkgReceiverRegistered) {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_PACKAGE_ADDED);
            f.addAction(Intent.ACTION_PACKAGE_REMOVED);
            f.addDataScheme("package");
            registerReceiver(pkgReceiver, f);
            pkgReceiverRegistered = true;
        }
        if (chip != null) chip.setText(chipText());
        Hub.loadChannels(false, null); // sedia cache saluran
    }

    @Override
    protected void onDestroy() {
        if (pkgReceiverRegistered) unregisterReceiver(pkgReceiver);
        super.onDestroy();
    }

    @Override
    public void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (firstTile != null) {
            scroll.smoothScrollTo(0, 0);
            firstTile.requestFocus();
        }
    }

    @Override
    void parallax(float nx, float ny) {
        bg.setParallax(nx, ny);
    }

    void reloadWallpaper() {
        final File f = Hub.wallpaperFile();
        if (!f.exists()) {
            bg.setWallpaper(null);
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap b = ImageViewerActivity.decode(f, 320, 180);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        bg.setWallpaper(b);
                    }
                });
            }
        }).start();
    }

    // ---------------------------------------------------------------- apps

    private void loadApps() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                PackageManager pm = getPackageManager();
                Map<String, AppInfo> map = new HashMap<>();
                for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
                    for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                        String pkg = ri.activityInfo.packageName;
                        if (pkg.equals(getPackageName()) || map.containsKey(pkg)) continue;
                        AppInfo a = new AppInfo();
                        a.pkg = pkg;
                        a.label = ri.loadLabel(pm).toString();
                        a.icon = ri.loadIcon(pm);
                        a.launch = pm.getLeanbackLaunchIntentForPackage(pkg);
                        if (a.launch == null) a.launch = pm.getLaunchIntentForPackage(pkg);
                        if (a.launch != null) map.put(pkg, a);
                    }
                }
                final List<AppInfo> list = new ArrayList<>(map.values());
                Collections.sort(list, new Comparator<AppInfo>() {
                    @Override
                    public int compare(AppInfo a, AppInfo b) {
                        return a.label.compareToIgnoreCase(b.label);
                    }
                });
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        apps = list;
                        rebuild();
                    }
                });
            }
        }, "apps").start();
    }

    private String chipText() {
        return "Remote  " + U.remoteUrl().replace("http://", "") + "   PIN " + Store.pin(this);
    }

    private TextView section(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xB3FFFFFF);
        t.setTextSize(16);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        t.setLetterSpacing(0.08f);
        t.setPadding((int) (8 * d), (int) (18 * d), 0, (int) (2 * d));
        return t;
    }

    private Rail newRail() {
        Rail r = new Rail(this);
        r.setPadding((int) (20 * d), (int) (6 * d), (int) (20 * d), (int) (10 * d));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins((int) (-20 * d), 0, (int) (-20 * d), 0);
        r.setLayoutParams(lp);
        return r;
    }

    private void rebuild() {
        StringBuilder sig = new StringBuilder();
        for (AppInfo a : apps) sig.append(a.pkg).append(',');
        sig.append(Store.favorites(this));
        String s = sig.toString();
        if (s.equals(appsSig) && content.getChildCount() > 0) return;
        appsSig = s;

        View focusedBefore = getCurrentFocus();
        String keepTitle = focusedBefore instanceof Tile ? ((Tile) focusedBefore).tag : null;

        content.removeAllViews();
        firstTile = null;
        Tile refocus = null;

        // ---- kepala: jam + chip remote
        FrameLayout header = new FrameLayout(this);
        LinearLayout clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("h:mm");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(64);
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        clock.setIncludeFontPadding(false);
        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE, d MMMM yyyy");
        date.setFormat12Hour("EEEE, d MMMM yyyy");
        date.setTextColor(0xCCFFFFFF);
        date.setTextSize(18);
        clockBox.addView(clock);
        clockBox.addView(date);
        header.addView(clockBox, new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));
        chip = new TextView(this) {
            @Override
            protected void onDraw(Canvas c) {
                Glass.plate(c, d, 0, 0, getWidth(), getHeight(), getHeight() / 2f, 0f, 0, 0, -1f);
                super.onDraw(c);
            }
        };
        chip.setText(chipText());
        chip.setTextColor(Color.WHITE);
        chip.setTextSize(15);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding((int) (22 * d), (int) (10 * d), (int) (22 * d), (int) (10 * d));
        header.addView(chip, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        content.addView(header, new LinearLayout.LayoutParams(-1, -2));

        // ---- utama
        content.addView(section("UTAMA"));
        Rail hero = newRail();
        Tile live = hero(R_TV(), "Live TV", "Saluran siaran langsung", 0xFF0A84FF, 0xFF5E5CE6, "hero:live");
        live.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open(new Intent(MainActivity.this, LiveTvActivity.class));
            }
        });
        Tile phone = hero(R_PHONE(), "Remote Telefon", U.remoteUrl().replace("http://", ""), 0xFFBF5AF2, 0xFFFF375F, "hero:remote");
        phone.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showRemoteInfo();
            }
        });
        Tile photo = hero(R_PHOTO(), "Galeri Telefon", "Gambar dihantar dari telefon", 0xFFFF9F0A, 0xFFFF375F, "hero:gallery");
        photo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Hub.images().isEmpty()) {
                    Toast.makeText(MainActivity.this, "Belum ada gambar. Hantar dari telefon (Remote > Gambar).", Toast.LENGTH_LONG).show();
                } else {
                    open(new Intent(MainActivity.this, ImageViewerActivity.class).putExtra("slideshow", true));
                }
            }
        });
        Tile set = hero(R_SET(), "Tetapan", "Launcher & sistem", 0xFF636366, 0xFF2C2C2E, "hero:settings");
        set.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        hero.add(live);
        hero.add(phone);
        hero.add(photo);
        hero.add(set);
        content.addView(hero);
        firstTile = live;
        refocus = live;

        // ---- kegemaran
        List<AppInfo> favs = new ArrayList<>();
        for (String p : Store.favorites(this)) for (AppInfo a : apps) if (a.pkg.equals(p)) favs.add(a);
        if (!favs.isEmpty()) {
            content.addView(section("KEGEMARAN"));
            Rail r = newRail();
            for (AppInfo a : favs) {
                Tile t = appTile(a, "fav:");
                r.add(t);
                if (t.tag.equals(keepTitle)) refocus = t;
            }
            content.addView(r);
        }

        // ---- semua apl (grid)
        content.addView(section("SEMUA APL"));
        int sw = getResources().getDisplayMetrics().widthPixels;
        int cols = Math.max(3, (int) ((sw - 112 * d) / (150 * d)));
        Rail row = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % cols == 0) {
                row = newRail();
                content.addView(row);
            }
            Tile t = appTile(apps.get(i), "app:");
            row.add(t);
            if (t.tag.equals(keepTitle)) refocus = t;
        }
        final Tile rf = refocus;
        content.post(new Runnable() {
            @Override
            public void run() {
                if (getCurrentFocus() == null || !getCurrentFocus().isAttachedToWindow()) rf.requestFocus();
            }
        });
    }

    private Drawable res(int id) {
        return getResources().getDrawable(id);
    }

    private Drawable R_TV() { return res(R.drawable.ic_tv); }
    private Drawable R_PHONE() { return res(R.drawable.ic_phone); }
    private Drawable R_PHOTO() { return res(R.drawable.ic_photo); }
    private Drawable R_SET() { return res(R.drawable.ic_settings); }

    private Tile hero(Drawable icon, String title, String sub, int a, int b, String tag) {
        Tile t = new Tile(this, icon, title, sub, true, a, b);
        t.tag = tag;
        return t;
    }

    private Tile appTile(final AppInfo a, String prefix) {
        final Tile t = new Tile(this, a.icon, a.label, null, false, 0, 0);
        t.tag = prefix + a.pkg;
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open(a.launch);
            }
        });
        t.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                appMenu(a);
                return true;
            }
        });
        return t;
    }

    private void open(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
            overridePendingTransition(R.anim.launch_in, R.anim.launch_out);
        } catch (Exception e) {
            Toast.makeText(this, "Tak dapat dibuka: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void appMenu(final AppInfo a) {
        final boolean fav = Store.isFavorite(this, a.pkg);
        new GlassMenu(this, a.label)
                .add("Buka", new Runnable() {
                    @Override
                    public void run() {
                        open(a.launch);
                    }
                })
                .add(fav ? "Buang dari Kegemaran" : "Tambah ke Kegemaran", new Runnable() {
                    @Override
                    public void run() {
                        Store.toggleFavorite(MainActivity.this, a.pkg);
                        rebuild();
                    }
                })
                .add("Maklumat Apl", new Runnable() {
                    @Override
                    public void run() {
                        open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg)));
                    }
                })
                .add("Nyahpasang", new Runnable() {
                    @Override
                    public void run() {
                        open(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg)));
                    }
                })
                .show();
    }

    private void showRemoteInfo() {
        new GlassMenu(this, "Kawal TV dari telefon")
                .note("1. Sambung telefon ke Wi-Fi yang sama dengan TV.", 15, 0xCCFFFFFF)
                .note("2. Buka pelayar telefon dan taip:", 15, 0xCCFFFFFF)
                .note(U.remoteUrl(), 26, 0xFF64D2FF)
                .note("3. Masukkan PIN:", 15, 0xCCFFFFFF)
                .note(Store.pin(this), 34, Color.WHITE)
                .note("Boleh remote, hantar gambar, pilih saluran TV, buka apl & taip teks.", 13, 0x99FFFFFF)
                .add("Tutup", null)
                .show();
    }

    @Override
    public void onRailFocus(Rail r, Tile t) {
        int target = (int) (r.getTop() - 110 * d);
        scroll.smoothScrollTo(0, Math.max(0, target));
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
        // launcher: Back kembali ke atas, tak keluar
        if (firstTile != null) {
            scroll.smoothScrollTo(0, 0);
            firstTile.requestFocus();
        }
    }
}
