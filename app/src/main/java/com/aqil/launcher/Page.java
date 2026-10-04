package com.aqil.launcher;

import android.view.View;
import android.widget.FrameLayout;

/** Satu halaman dalam launcher (Home, Apl, Live TV, Remote, Tetapan). */
abstract class Page {
    final MainActivity a;
    final FrameLayout root;

    Page(MainActivity a) {
        this.a = a;
        root = new FrameLayout(a);
        Ui.noClip(root);
    }

    /** Bina semula kandungan (dipanggil setiap kali halaman dipaparkan / data berubah). */
    abstract void build();

    /** Paparan pertama yang patut dapat fokus. */
    View firstView;

    View first() {
        return firstView;
    }

    void onHide() {}
}
