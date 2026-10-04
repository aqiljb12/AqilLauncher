package com.aqil.launcher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Bagi telefon kawal apl LAIN (bukan launcher sahaja): Back/Home/Recents, navigasi fokus,
 * OK, sentuhan/leret dan menaip teks. Pengguna mesti hidupkan di Tetapan > Kebolehcapaian.
 *
 * Servis ini HANYA bertindak bila telefon menghantar arahan: acara kebolehcapaian cuma dipakai untuk
 * mengingati paparan terakhir yang difokus (tiada tindakan automatik, tiada gelung acara), dan kekunci
 * fizikal remote tidak dipintas. Setiap kaedah pulangkan false (bukan crash) bila apl tiada nod yang boleh guna.
 */
public class RemoteAccessibilityService extends AccessibilityService {
    static volatile RemoteAccessibilityService instance;
    /** Nod terakhir yang dilaporkan menerima fokus (sesetengah apl TV tak menjawab findFocus()). */
    private volatile AccessibilityNodeInfo lastFocused;
    private static final int MAX_NODES = 1500;
    private final Handler main = new Handler(Looper.getMainLooper());

    // ---- kursor tetikus (overlay kebolehcapaian: tiada kebenaran tambahan, tidak menerima sentuhan)
    private CursorView cursor;
    private WindowManager.LayoutParams cursorLp;
    private volatile float cx = -1, cy = -1;
    private final Runnable hideCursor = new Runnable() {
        @Override
        public void run() {
            if (cursor != null) cursor.setVisibility(View.GONE);
        }
    };

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        try {
            int t = e.getEventType();
            if (t == AccessibilityEvent.TYPE_VIEW_FOCUSED || t == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
                AccessibilityNodeInfo src = e.getSource();
                if (src != null) lastFocused = src;
            } else if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                lastFocused = null; // skrin/apl bertukar: fokus lama tak lagi sah
            }
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void onInterrupt() {}

    @Override
    public boolean onUnbind(android.content.Intent i) {
        if (instance == this) instance = null;
        lastFocused = null;
        removeCursor();
        return super.onUnbind(i);
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        lastFocused = null;
        removeCursor();
        super.onDestroy();
    }

    boolean global(int action) {
        try {
            return performGlobalAction(action);
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- mencari nod

    /** Akar tetingkap aktif; jika tiada, tetingkap aplikasi yang difokus/aktif. */
    private AccessibilityNodeInfo root() {
        AccessibilityNodeInfo r = getRootInActiveWindow();
        if (r != null) return r;
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            for (AccessibilityWindowInfo w : ws) if (w.isFocused() && w.getRoot() != null) return w.getRoot();
            for (AccessibilityWindowInfo w : ws) {
                if (w.isActive() && w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && w.getRoot() != null) return w.getRoot();
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private AccessibilityNodeInfo focusedNode(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (f == null) f = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        if (f == null) {
            AccessibilityNodeInfo l = lastFocused;
            if (l != null && l.refresh() && l.isVisibleToUser()) f = l;
        }
        return f;
    }

    /** Semua nod boleh-fokus yang kelihatan (carian lebar, terhad supaya tak berat). */
    private List<AccessibilityNodeInfo> focusables(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int seen = 0;
        while (!q.isEmpty() && seen++ < MAX_NODES) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null || !n.isVisibleToUser()) continue;
            if (n.isFocusable() && n.isEnabled()) out.add(n);
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return out;
    }

    /** Pilih nod terdekat ke arah dir dari kotak cur (gaya FocusFinder: jarak utama + jarak sisi). */
    private static AccessibilityNodeInfo nearest(List<AccessibilityNodeInfo> nodes, Rect cur, int dir) {
        AccessibilityNodeInfo best = null;
        long bestScore = Long.MAX_VALUE;
        Rect r = new Rect();
        for (AccessibilityNodeInfo n : nodes) {
            n.getBoundsInScreen(r);
            if (r.isEmpty() || r.equals(cur)) continue;
            long major, minor;
            switch (dir) {
                case View.FOCUS_UP:
                    if (r.bottom > cur.top + 1 && r.centerY() >= cur.centerY()) continue;
                    major = cur.top - r.bottom;
                    minor = r.centerX() - cur.centerX();
                    break;
                case View.FOCUS_DOWN:
                    if (r.top < cur.bottom - 1 && r.centerY() <= cur.centerY()) continue;
                    major = r.top - cur.bottom;
                    minor = r.centerX() - cur.centerX();
                    break;
                case View.FOCUS_LEFT:
                    if (r.right > cur.left + 1 && r.centerX() >= cur.centerX()) continue;
                    major = cur.left - r.right;
                    minor = r.centerY() - cur.centerY();
                    break;
                default:
                    if (r.left < cur.right - 1 && r.centerX() <= cur.centerX()) continue;
                    major = r.left - cur.right;
                    minor = r.centerY() - cur.centerY();
                    break;
            }
            major = Math.max(0, major);
            long score = 13 * major * major + minor * minor;
            if (score < bestScore) {
                bestScore = score;
                best = n;
            }
        }
        return best;
    }

    private static boolean focus(AccessibilityNodeInfo n) {
        return n != null && (n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                || n.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS));
    }

    // ---------------------------------------------------------------- tindakan

    /** Gerak fokus ke arah (View.FOCUS_*) dalam apl yang sedang depan. */
    boolean move(int direction) {
        try {
            AccessibilityNodeInfo root = root();
            if (root == null) return false;
            AccessibilityNodeInfo cur = focusedNode(root);
            if (cur == null) {
                List<AccessibilityNodeInfo> all = focusables(root);
                return !all.isEmpty() && focus(all.get(0));
            }
            AccessibilityNodeInfo next = cur.focusSearch(direction);
            if (next != null && !next.equals(cur) && focus(next)) return true;
            // focusSearch tiada/gagal (biasa pada apl TV): cari secara geometri
            Rect b = new Rect();
            cur.getBoundsInScreen(b);
            return focus(nearest(focusables(root), b, direction));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** OK: klik paparan yang difokus. Tiada fokus = gagal (tiada klik buta di tengah skrin). */
    boolean click() {
        try {
            AccessibilityNodeInfo root = root();
            if (root == null) return false;
            AccessibilityNodeInfo focused = focusedNode(root);
            if (focused == null) return false;
            AccessibilityNodeInfo cur = focused;
            for (int depth = 0; cur != null && !cur.isClickable() && depth < 8; depth++) cur = cur.getParent();
            if (cur != null && cur.isClickable() && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            // apl tak terima ACTION_CLICK: sentuh tengah paparan YANG DIFOKUS itu sahaja
            Rect r = new Rect();
            focused.getBoundsInScreen(r);
            if (r.isEmpty()) return false;
            DisplayMetrics m = metrics();
            return tap(r.exactCenterX() / m.widthPixels, r.exactCenterY() / m.heightPixels);
        } catch (RuntimeException e) {
            return false;
        }
    }

    boolean setText(String text, boolean append) {
        try {
            AccessibilityNodeInfo root = root();
            if (root == null) return false;
            AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (f == null || !f.isEditable()) {
                AccessibilityNodeInfo l = lastFocused;
                f = l != null && l.refresh() && l.isEditable() ? l : null;
            }
            if (f == null) {
                // tiada kotak teks berfokus: guna kotak teks jika HANYA ada satu yang kelihatan
                AccessibilityNodeInfo only = null;
                for (AccessibilityNodeInfo n : focusables(root)) {
                    if (!n.isEditable()) continue;
                    if (only != null) return false;
                    only = n;
                }
                f = only;
            }
            if (f == null) return false;
            CharSequence old = f.getText();
            String v = append && old != null ? old + text : text;
            Bundle b = new Bundle();
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, v);
            return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private DisplayMetrics metrics() {
        DisplayMetrics m = new DisplayMetrics();
        ((android.view.WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(m);
        return m;
    }

    boolean tap(float x, float y) {
        return swipe(x, y, x, y, 60);
    }

    /** Koordinat 0..1 mengikut saiz skrin. Menunggu sistem selesaikan gerak isyarat (bukan sekadar hantar). */
    boolean swipe(float x1, float y1, float x2, float y2, long ms) {
        if (Build.VERSION.SDK_INT < 24) return false;
        if (Float.isNaN(x1) || Float.isNaN(y1)) return false;
        DisplayMetrics m = metrics();
        float ax = U.clamp(x1, 0, 1) * (m.widthPixels - 1), ay = U.clamp(y1, 0, 1) * (m.heightPixels - 1);
        float bx = Float.isNaN(x2) ? ax : U.clamp(x2, 0, 1) * (m.widthPixels - 1);
        float by = Float.isNaN(y2) ? ay : U.clamp(y2, 0, 1) * (m.heightPixels - 1);
        return gesture(ax, ay, bx, by, ms);
    }

    /** Hantar gerak isyarat dalam piksel skrin dan tunggu keputusan (selesai / dibatalkan sistem). */
    private boolean gesture(float ax, float ay, float bx, float by, long ms) {
        if (Build.VERSION.SDK_INT < 24) return false;
        try {
            Path p = new Path();
            p.moveTo(ax, ay);
            if (ax != bx || ay != by) p.lineTo(bx, by);
            else p.lineTo(ax + 1, ay); // garisan 1px: sesetengah peranti menolak laluan satu titik
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(1, Math.min(ms, 5000)))).build();
            final CountDownLatch done = new CountDownLatch(1);
            final boolean[] ok = {false};
            boolean sent = dispatchGesture(g, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription d) {
                    ok[0] = true;
                    done.countDown();
                }

                @Override
                public void onCancelled(GestureDescription d) {
                    done.countDown();
                }
            }, main);
            if (!sent) return false;
            if (Looper.myLooper() == Looper.getMainLooper()) return true; // jangan sekat thread utama
            done.await(ms + 1500, TimeUnit.MILLISECONDS);
            return ok[0];
        } catch (RuntimeException | InterruptedException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- mod tetikus

    /** Gerak kursor secara relatif (dx,dy = pecahan lebar/tinggi skrin, seperti trackpad komputer riba). */
    boolean cursorMove(float dx, float dy) {
        if (Build.VERSION.SDK_INT < 22) return false;
        if (Float.isNaN(dx) || Float.isInfinite(dx)) dx = 0;
        if (Float.isNaN(dy) || Float.isInfinite(dy)) dy = 0;
        DisplayMetrics m = metrics();
        if (cx < 0) {
            cx = m.widthPixels / 2f;
            cy = m.heightPixels / 2f;
        }
        cx = U.clamp(cx + dx * m.widthPixels, 0, m.widthPixels - 1);
        cy = U.clamp(cy + dy * m.heightPixels, 0, m.heightPixels - 1);
        main.post(new Runnable() {
            @Override
            public void run() {
                showCursor();
            }
        });
        return true;
    }

    /** Klik di kedudukan kursor. */
    boolean cursorClick() {
        if (cx < 0) {
            cursorMove(0, 0);
            return true; // klik pertama hanya munculkan kursor di tengah
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                showCursor();
            }
        });
        return gesture(cx, cy, cx, cy, 60);
    }

    /** Leret (skrol) bermula di kursor; dx,dy pecahan skrin. */
    boolean cursorScroll(float dx, float dy) {
        if (Float.isNaN(dx)) dx = 0;
        if (Float.isNaN(dy)) dy = 0;
        DisplayMetrics m = metrics();
        if (cx < 0) cursorMove(0, 0);
        float bx = U.clamp(cx + dx * m.widthPixels, 0, m.widthPixels - 1);
        float by = U.clamp(cy + dy * m.heightPixels, 0, m.heightPixels - 1);
        return gesture(cx, cy, bx, by, 280);
    }

    private void showCursor() {
        try {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            int size = Math.round(getResources().getDisplayMetrics().density * 34);
            if (cursor == null) {
                cursor = new CursorView(this);
                cursorLp = new WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                cursorLp.gravity = Gravity.TOP | Gravity.START;
                cursorLp.x = Math.round(cx);
                cursorLp.y = Math.round(cy);
                wm.addView(cursor, cursorLp);
            } else {
                cursorLp.x = Math.round(cx);
                cursorLp.y = Math.round(cy);
                cursor.setVisibility(View.VISIBLE);
                wm.updateViewLayout(cursor, cursorLp);
            }
            main.removeCallbacks(hideCursor);
            main.postDelayed(hideCursor, 8000);
        } catch (RuntimeException ignored) {
        }
    }

    private void removeCursor() {
        main.removeCallbacks(hideCursor);
        if (cursor != null) {
            try {
                ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).removeView(cursor);
            } catch (RuntimeException ignored) {
            }
            cursor = null;
        }
        cx = cy = -1;
    }

    /** Anak panah tetikus putih dengan bingkai gelap (hujung di penjuru kiri atas = titik klik). */
    private static final class CursorView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();

        CursorView(Context c) {
            super(c);
            fill.setColor(0xFFFFFFFF);
            edge.setColor(0xE6000000);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onDraw(Canvas c) {
            float u = getWidth() / 24f;
            edge.setStrokeWidth(1.6f * u);
            arrow.reset();
            arrow.moveTo(1.5f * u, 1.5f * u);
            arrow.lineTo(1.5f * u, 19 * u);
            arrow.lineTo(6.2f * u, 14.6f * u);
            arrow.lineTo(9.6f * u, 22 * u);
            arrow.lineTo(12.6f * u, 20.6f * u);
            arrow.lineTo(9.3f * u, 13.4f * u);
            arrow.lineTo(15.5f * u, 13.4f * u);
            arrow.close();
            c.drawPath(arrow, fill);
            c.drawPath(arrow, edge);
        }
    }

    static int direction(String k) {
        switch (k) {
            case "up": return View.FOCUS_UP;
            case "down": return View.FOCUS_DOWN;
            case "left": return View.FOCUS_LEFT;
            default: return View.FOCUS_RIGHT;
        }
    }
}
