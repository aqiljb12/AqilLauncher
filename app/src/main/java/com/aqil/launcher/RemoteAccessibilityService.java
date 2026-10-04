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

/**
 * Bagi telefon kawal apl LAIN (bukan launcher sahaja): Back/Home/Recents, navigasi fokus,
 * sentuhan/leret dan menaip teks. Pengguna mesti hidupkan di Tetapan > Kebolehcapaian.
 */
public class RemoteAccessibilityService extends AccessibilityService {
    static volatile RemoteAccessibilityService instance;

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {}

    @Override
    public void onInterrupt() {}

    @Override
    public boolean onUnbind(android.content.Intent i) {
        instance = null;
        return super.onUnbind(i);
    }

    boolean global(int action) {
        return performGlobalAction(action);
    }

    private AccessibilityNodeInfo focusedNode(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (f == null) f = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        return f;
    }

    private AccessibilityNodeInfo firstFocusable(AccessibilityNodeInfo n) {
        if (n == null) return null;
        if (n.isFocusable() && n.isVisibleToUser() && n.getChildCount() == 0) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = firstFocusable(n.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    /** Gerak fokus ke arah (View.FOCUS_*) dalam apl yang sedang depan. */
    boolean move(int direction) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo cur = focusedNode(root);
        AccessibilityNodeInfo next = cur == null ? firstFocusable(root) : cur.focusSearch(direction);
        if (next == null) return false;
        return next.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                || next.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
    }

    boolean click() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo cur = focusedNode(root);
        while (cur != null && !cur.isClickable()) cur = cur.getParent();
        if (cur != null) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        // tiada fokus: tap tengah skrin
        return tap(0.5f, 0.5f);
    }

    boolean setText(String text, boolean append) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (f == null || !f.isEditable()) return false;
        CharSequence old = f.getText();
        String v = append && old != null ? old + text : text;
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, v);
        return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
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
        DisplayMetrics m = metrics();
        Path p = new Path();
        p.moveTo(x1 * m.widthPixels, y1 * m.heightPixels);
        if (x1 != x2 || y1 != y2) p.lineTo(x2 * m.widthPixels, y2 * m.heightPixels);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(ms, 1))).build();
        return dispatchGesture(g, null, null);
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
