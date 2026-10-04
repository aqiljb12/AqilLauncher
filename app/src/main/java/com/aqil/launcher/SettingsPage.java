package com.aqil.launcher;

import android.content.Intent;
import android.graphics.Bitmap;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/** Tetapan: wallpaper (live/biasa/gambar/video), remote, Live TV, cuaca, animasi, sistem. */
final class SettingsPage extends Page {
    private ScrollView sv;

    SettingsPage(MainActivity a) {
        super(a);
    }

    @Override
    void build() {
        root.removeAllViews();
        root.addView(Ui.text(a, "Tetapan", 46, Ui.WHITE, Ui.BOLD), Ui.at(6, 0, -2, -2));
        sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout col = new FrontLayout(a, LinearLayout.VERTICAL);
        col.setPadding(S.px(14), S.px(16), S.px(14), S.px(80));
        Ui.noClip(col);
        Ui.clipToBounds(sv);
        sv.addView(col);

        col.addView(section("Wallpaper"));
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout walls = new FrontLayout(a, LinearLayout.HORIZONTAL);
        walls.setPadding(S.px(6), S.px(16), S.px(30), S.px(20));
        Ui.noClip(walls);
        Ui.clipToBounds(hs);
        hs.addView(walls);
        String cur = Store.wallpaper(a);
        firstView = null;
        addWall(walls, "aurora", "Aurora  •  LIVE", null, cur);
        addWall(walls, "nebula", "Nebula  •  LIVE", null, cur);
        if (Hub.videoWallpaperFile().exists()) addWall(walls, "video", "Video  •  LIVE", null, cur);
        addWall(walls, "grad:night", "Malam", null, cur);
        addWall(walls, "grad:dusk", "Senja", null, cur);
        List<File> imgs = Hub.images();
        for (int i = 0; i < imgs.size() && i < 10; i++) addWall(walls, "photo:" + imgs.get(i).getName(), "Gambar", imgs.get(i), cur);
        col.addView(hs);
        TextView tip = Ui.text(a, "Hantar gambar atau video (wallpaper live) dari telefon: Remote › Lagi › Wallpaper.", 22, Ui.FAINT, Ui.MEDIUM);
        col.addView(tip);

        col.addView(section("Umum"));
        row(col, "Remote telefon", U.remoteUrl() + "   •   PIN " + Store.pin(a), "Lihat", new Runnable() {
            @Override
            public void run() {
                a.showPage("remote");
            }
        });
        boolean a11y = RemoteAccessibilityService.instance != null;
        row(col, "Kawal apl lain dari telefon", "Back / Home / sentuhan / taip teks di luar launcher", a11y ? "Aktif ✓" : "Hidupkan", new Runnable() {
            @Override
            public void run() {
                a.openSafe(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        row(col, "Animasi 3D & wallpaper live", Store.fx(a) ? "Hidup" : "Mati – jimat tenaga untuk TV box lemah", Store.fx(a) ? "ON" : "OFF", new Runnable() {
            @Override
            public void run() {
                Store.setFx(a, !Store.fx(a));
                a.reloadWallpaper();
                rebuildKeepFocus();
            }
        });
        String[] place = Store.weatherPlace(a);
        row(col, "Cuaca", (place != null ? place[0] : (Weather.place.isEmpty() ? "Lokasi automatik (IP)" : Weather.place + " (auto)"))
                + "  •  tukar bandar dari telefon", Weather.tempText(), new Runnable() {
            @Override
            public void run() {
                Weather.refresh(a, true, new Runnable() {
                    @Override
                    public void run() {
                        a.updateWeather();
                        rebuildKeepFocus();
                    }
                });
            }
        });

        col.addView(section("Live TV"));
        final String[] xt = Store.xtream(a);
        row(col, xt != null ? "Akaun IPTV (Xtream Codes)" : "Log masuk akaun IPTV (Xtream / Smarters)",
                xt != null ? Xtream.summary() : "Server (DNS) + username + password – atau isi dari telefon (Lagi › Akaun IPTV)",
                xt != null ? "Tukar" : "Log masuk", new Runnable() {
                    @Override
                    public void run() {
                        loginDialog(xt);
                    }
                });
        if (xt != null) row(col, "Log keluar akaun IPTV", "Kembali guna senarai M3U", null, new Runnable() {
            @Override
            public void run() {
                Xtream.logout(a);
                Hub.loadChannels(true, null);
                Toast.makeText(a, "Log keluar. Guna senarai M3U.", Toast.LENGTH_LONG).show();
                rebuildKeepFocus();
            }
        });
        if (xt == null) row(col, "Senarai saluran (M3U)", Store.playlist(a), "Set semula", new Runnable() {
            @Override
            public void run() {
                Store.setPlaylist(a, null);
                Hub.loadChannels(true, null);
                Toast.makeText(a, "Guna senarai asal. Tukar URL dari telefon (Remote › Lagi).", Toast.LENGTH_LONG).show();
                rebuildKeepFocus();
            }
        });
        row(col, "Kualiti video Live TV", Store.maxQuality(a)
                        ? "Tertinggi – sentiasa pilih resolusi paling tinggi yang siaran sediakan"
                        : "Auto – ikut kelajuan Internet (jimat data)", Store.maxQuality(a) ? "Tertinggi" : "Auto", new Runnable() {
                    @Override
                    public void run() {
                        Store.setMaxQuality(a, !Store.maxQuality(a));
                        rebuildKeepFocus();
                    }
                });
        row(col, "Langkau saluran rosak automatik", "Bila saluran gagal, terus ke saluran seterusnya", Store.autoSkip(a) ? "ON" : "OFF", new Runnable() {
            @Override
            public void run() {
                Store.setAutoSkip(a, !Store.autoSkip(a));
                rebuildKeepFocus();
            }
        });

        col.addView(section("Sistem"));
        row(col, "Jadikan launcher lalai", "Tetapan Home sistem", null, new Runnable() {
            @Override
            public void run() {
                a.openSafe(new Intent(Settings.ACTION_HOME_SETTINGS));
            }
        });
        row(col, "Wi-Fi & rangkaian", null, null, new Runnable() {
            @Override
            public void run() {
                a.openSafe(new Intent(Settings.ACTION_WIFI_SETTINGS));
            }
        });
        row(col, "Tetapan Android", null, null, new Runnable() {
            @Override
            public void run() {
                a.openSafe(new Intent(Settings.ACTION_SETTINGS));
            }
        });
        TextView v = Ui.text(a, "Aqil Launcher " + version(), 22, Ui.FAINT, Ui.MEDIUM);
        v.setPadding(S.px(8), S.px(30), 0, 0);
        col.addView(v);
        root.addView(sv, Ui.at(-14, 70, 1680, 794));
    }

    private void loginDialog(String[] cur) {
        GlassMenu m = new GlassMenu(a, "Akaun IPTV (Xtream Codes)");
        final android.widget.EditText server = m.field("Server / DNS (cth http://server.com:8080)", cur != null ? cur[0] : "", false);
        final android.widget.EditText user = m.field("Username", cur != null ? cur[1] : "", false);
        final android.widget.EditText pass = m.field("Password", cur != null ? cur[2] : "", true);
        m.add("Log masuk", new Runnable() {
            @Override
            public void run() {
                final String s = server.getText().toString(), u = user.getText().toString(), p = pass.getText().toString();
                Toast.makeText(a, "Log masuk…", Toast.LENGTH_SHORT).show();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String err = Xtream.login(a, s, u, p);
                        Hub.main.post(new Runnable() {
                            @Override
                            public void run() {
                                if (err != null) {
                                    Toast.makeText(a, err, Toast.LENGTH_LONG).show();
                                    return;
                                }
                                Hub.loadChannels(true, new Hub.Done() {
                                    @Override
                                    public void run(int count, String e) {
                                        Toast.makeText(a, count > 0 ? "Berjaya! " + count + " saluran" : "Gagal muat saluran: " + e, Toast.LENGTH_LONG).show();
                                        if (root.isAttachedToWindow()) rebuildKeepFocus();
                                    }
                                });
                            }
                        });
                    }
                }).start();
            }
        }).add("Batal", null).show();
    }

    private String version() {
        try {
            return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private void rebuildKeepFocus() {
        final int y = sv.getScrollY();
        View f = a.getCurrentFocus();
        final String key = f != null && f.getTag() instanceof String ? (String) f.getTag() : null;
        build();
        sv.post(new Runnable() {
            @Override
            public void run() {
                sv.scrollTo(0, y);
                View t = key == null ? null : root.findViewWithTag(key);
                if (t != null) t.requestFocus();
                else if (firstView != null) firstView.requestFocus();
            }
        });
    }

    private TextView section(String s) {
        TextView t = Ui.text(a, s.toUpperCase(), 24, Ui.DIM, Ui.BOLD);
        t.setLetterSpacing(0.12f);
        t.setPadding(S.px(8), S.px(30), 0, S.px(6));
        return t;
    }

    private void row(LinearLayout col, String title, String sub, String right, final Runnable r) {
        Row row = new Row(a, title, sub, right);
        row.flat();
        row.setTag("row:" + title);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(S.px(1400), S.px(sub == null ? 84 : 100));
        lp.topMargin = S.px(12);
        col.addView(row, lp);
        final Row me = row;
        row.onFocus = new Card.OnFocus() {
            @Override
            public void onFocus(Card c, boolean gained) {
                if (gained) sv.smoothScrollTo(0, Math.max(0, me.getTop() - S.px(260)));
            }
        };
    }

    private void addWall(LinearLayout walls, final String spec, String label, final File photo, String cur) {
        boolean active = spec.equals(cur);
        Card c = new Card(a, 22, WallpaperView.preview(spec)).flat();
        ((android.graphics.drawable.GradientDrawable) c.getBackground()).setCornerRadius(S.px(22));
        c.setTag("wall:" + spec);
        if (photo != null) {
            final ImageView iv = new ImageView(a);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            c.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final Bitmap b = ImageViewerActivity.decode(photo, S.px(260), S.px(146));
                    iv.post(new Runnable() {
                        @Override
                        public void run() {
                            iv.setImageBitmap(b);
                        }
                    });
                }
            }).start();
        }
        TextView t = Ui.text(a, (active ? "✓ " : "") + label, 22, Ui.WHITE, Ui.MEDIUM);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.solid(0x88000000, 0));
        t.setPadding(0, S.px(8), 0, S.px(8));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        c.addView(t, lp);
        if (active) {
            View mark = new View(a);
            mark.setBackground(Ui.selected(S.px(22)));
            c.addView(mark, new FrameLayout.LayoutParams(-1, -1));
        }
        c.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Hub.setWallpaper(spec);
                rebuildKeepFocus();
            }
        });
        walls.addView(c, Ui.lin(260, 146, 22));
        if (firstView == null) firstView = c;
    }
}
