package com.aqil.launcher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
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
        return super.onUnbind(i);
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        lastFocused = null;
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

    /** Koordinat 0..1 mengikut saiz skrin. */
    boolean swipe(float x1, float y1, float x2, float y2, long ms) {
        if (Build.VERSION.SDK_INT < 24) return false;
        if (Float.isNaN(x1) || Float.isNaN(y1)) return false;
        try {
            DisplayMetrics m = metrics();
            Path p = new Path();
            p.moveTo(U.clamp(x1, 0, 1) * (m.widthPixels - 1), U.clamp(y1, 0, 1) * (m.heightPixels - 1));
            if (!Float.isNaN(x2) && !Float.isNaN(y2) && (x1 != x2 || y1 != y2))
                p.lineTo(U.clamp(x2, 0, 1) * (m.widthPixels - 1), U.clamp(y2, 0, 1) * (m.heightPixels - 1));
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(1, Math.min(ms, 5000)))).build();
            return dispatchGesture(g, null, null);
        } catch (RuntimeException e) {
            return false;
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
