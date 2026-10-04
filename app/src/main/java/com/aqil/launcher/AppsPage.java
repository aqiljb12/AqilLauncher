package com.aqil.launcher;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import java.util.List;

/** Semua aplikasi dalam grid banner. Mod "pilih" untuk tambah ke Kegemaran. */
final class AppsPage extends Page {
    boolean pickMode;

    AppsPage(MainActivity a) {
        super(a);
    }

    @Override
    void build() {
        root.removeAllViews();
        List<Apps.A> all = Apps.all;
        root.addView(Ui.text(a, pickMode ? "Pilih apl untuk Kegemaran" : "Semua Aplikasi", 46, Ui.WHITE, Ui.BOLD), Ui.at(6, 0, -2, -2));
        root.addView(Ui.text(a, all.size() + " apl  •  Tekan lama OK / Menu untuk pilihan", 24, Ui.DIM, Ui.MEDIUM), Ui.at(8, 62, -2, -2));

        final ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        final LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(S.px(14), S.px(24), S.px(14), S.px(60));
        Ui.noClip(sv, col);
        sv.addView(col);
        LinearLayout row = null;
        firstView = null;
        for (int i = 0; i < all.size(); i++) {
            if (i % 6 == 0) {
                row = new LinearLayout(a);
                Ui.noClip(row);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.bottomMargin = S.px(28);
                col.addView(row, lp);
            }
            final Apps.A app = all.get(i);
            final Card c = a.appCard(app, 250, 141);
            if (pickMode) {
                c.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        boolean was = Store.isFavorite(a, app.pkg);
                        Store.toggleFavorite(a, app.pkg);
                        Toast.makeText(a, app.label + (was ? " dibuang dari" : " ditambah ke") + " Kegemaran", Toast.LENGTH_SHORT).show();
                        pickMode = false;
                        a.showPage("home");
                    }
                });
            }
            final LinearLayout r = row;
            c.onFocus = new Card.OnFocus() {
                @Override
                public void onFocus(Card card, boolean gained) {
                    if (gained) sv.smoothScrollTo(0, Math.max(0, r.getTop() - S.px(150)));
                }
            };
            row.addView(c, Ui.lin(250, 141, 22));
            if (firstView == null) firstView = c;
        }
        root.addView(sv, Ui.at(0, 96, 1660, 684));
    }

    @Override
    void onHide() {
        pickMode = false;
    }
}
