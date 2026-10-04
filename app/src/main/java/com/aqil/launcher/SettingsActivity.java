package com.aqil.launcher;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends BaseActivity {
    private BackgroundView bg;
    private LinearLayout list;
    private float d;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = U.dp(this, 1);
        FrameLayout root = new FrameLayout(this);
        bg = new BackgroundView(this);
        bg.setAnimated(Store.fx(this));
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding((int) (160 * d), (int) (40 * d), (int) (160 * d), (int) (60 * d));
        sv.addView(list);
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
    }

    private void build() {
        list.removeAllViews();
        TextView t = new TextView(this);
        t.setText("Tetapan");
        t.setTextColor(Color.WHITE);
        t.setTextSize(40);
        t.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        t.setPadding((int) (8 * d), 0, 0, (int) (14 * d));
        list.addView(t);

        row("Remote telefon", U.remoteUrl() + "   •   PIN " + Store.pin(this), "Tukar PIN", new Runnable() {
            @Override
            public void run() {
                Store.newPin(SettingsActivity.this);
                Toast.makeText(SettingsActivity.this, "PIN baharu: " + Store.pin(SettingsActivity.this) + " (telefon perlu masuk semula)", Toast.LENGTH_LONG).show();
                build();
            }
        });
        boolean a11y = RemoteAccessibilityService.instance != null;
        row("Kawal apl lain dari telefon", "Back/Home/sentuhan/taip teks di luar launcher", a11y ? "Aktif" : "Hidupkan", new Runnable() {
            @Override
            public void run() {
                open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        row("Senarai saluran Live TV", Store.playlist(this), "Set semula", new Runnable() {
            @Override
            public void run() {
                Store.setPlaylist(SettingsActivity.this, null);
                Hub.loadChannels(true, null);
                Toast.makeText(SettingsActivity.this, "Guna senarai asal. Tukar URL dari telefon.", Toast.LENGTH_LONG).show();
                build();
            }
        });
        row("Animasi latar", Store.fx(this) ? "Hidup — latar aurora bergerak" : "Mati — jimat tenaga (untuk TV box lemah)",
                Store.fx(this) ? "ON" : "OFF", new Runnable() {
                    @Override
                    public void run() {
                        Store.setFx(SettingsActivity.this, !Store.fx(SettingsActivity.this));
                        bg.setAnimated(Store.fx(SettingsActivity.this));
                        build();
                    }
                });
        row("Buang wallpaper tersuai", "Kembali ke latar aurora", null, new Runnable() {
            @Override
            public void run() {
                Hub.wallpaperFile().delete();
                Toast.makeText(SettingsActivity.this, "Wallpaper dibuang", Toast.LENGTH_SHORT).show();
            }
        });
        row("Jadikan launcher lalai", "Buka tetapan Home sistem", null, new Runnable() {
            @Override
            public void run() {
                open(new Intent(Settings.ACTION_HOME_SETTINGS));
            }
        });
        row("Wi-Fi", "Tetapan rangkaian", null, new Runnable() {
            @Override
            public void run() {
                open(new Intent(Settings.ACTION_WIFI_SETTINGS));
            }
        });
        row("Tetapan sistem", "Android", null, new Runnable() {
            @Override
            public void run() {
                open(new Intent(Settings.ACTION_SETTINGS));
            }
        });
        TextView v = new TextView(this);
        v.setText("Aqil Launcher 2.0");
        v.setTextColor(0x80FFFFFF);
        v.setPadding((int) (8 * d), (int) (20 * d), 0, 0);
        list.addView(v);
        if (list.getChildCount() > 1) list.getChildAt(1).requestFocus();
    }

    private void row(String title, String sub, String right, final Runnable action) {
        GlassButton b = new GlassButton(this, title, sub, 0, 76);
        b.set(title, sub, right);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        list.addView(b);
    }

    private void open(Intent i) {
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Tak disokong pada peranti ini", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    void parallax(float nx, float ny) {
        bg.setParallax(nx, ny);
    }
}
