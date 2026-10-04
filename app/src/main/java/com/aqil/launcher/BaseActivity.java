package com.aqil.launcher;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

/** Skrin penuh imersif + jejak aktiviti paling depan supaya telefon boleh hantar kekunci. */
public class BaseActivity extends Activity {
    /** Arah D-pad terakhir (View.FOCUS_*) – untuk animasi 3D condong-masuk pada kad. */
    static int lastDir;
    volatile boolean windowFocused;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        S.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        RemoteService.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Hub.setTop(this);
        immersive();
    }

    @Override
    protected void onPause() {
        Hub.clearTop(this);
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        windowFocused = hasFocus;
        if (hasFocus) immersive();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT: lastDir = View.FOCUS_LEFT; break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: lastDir = View.FOCUS_RIGHT; break;
                case KeyEvent.KEYCODE_DPAD_UP: lastDir = View.FOCUS_UP; break;
                case KeyEvent.KEYCODE_DPAD_DOWN: lastDir = View.FOCUS_DOWN; break;
                default: break;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | (Build.VERSION.SDK_INT >= 19 ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY : 0));
    }

    /** Dipanggil oleh Card bila fokus bergerak; nx,ny dalam -1..1. */
    void parallax(float nx, float ny) {}

    /** Public supaya Hub boleh hantar intent baharu ke aktiviti yang sedang buka. */
    @Override
    public void onNewIntent(android.content.Intent i) {
        super.onNewIntent(i);
        setIntent(i);
    }
}
