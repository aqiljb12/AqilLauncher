package com.aqil.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.view.KeyEvent;

/** Terjemah arahan dari telefon kepada tindakan pada TV. Pulangkan null jika OK, atau mesej ralat. */
final class RemoteControl {
    static final String NEED_A11Y = "Hidupkan 'Aqil Launcher Remote' di Tetapan > Kebolehcapaian untuk kawal apl lain.";

    private RemoteControl() {}

    static void goHome(Context c) {
        c.startActivity(new Intent(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    static String key(final Context c, String k) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        switch (k) {
            case "volup":
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                return null;
            case "voldown":
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                return null;
            case "mute":
                if (Build.VERSION.SDK_INT >= 23) {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
                } else {
                    am.setStreamMute(AudioManager.STREAM_MUSIC, !am.isStreamMute(AudioManager.STREAM_MUSIC));
                }
                return null;
            case "home":
                try {
                    goHome(c);
                    return null;
                } catch (Exception e) {
                    RemoteAccessibilityService a = RemoteAccessibilityService.instance;
                    if (a != null) {
                        a.global(AccessibilityService.GLOBAL_ACTION_HOME);
                        return null;
                    }
                    return NEED_A11Y;
                }
            case "playpause":
                media(am, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                return null;
            case "ff":
                media(am, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
                return null;
            case "rew":
                media(am, KeyEvent.KEYCODE_MEDIA_REWIND);
                return null;
            default:
                break;
        }

        final int code = code(k);
        final BaseActivity top = Hub.top();
        if (top != null && code != 0) {
            Hub.main.post(new Runnable() {
                @Override
                public void run() {
                    top.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                    top.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
                }
            });
            return null;
        }

        RemoteAccessibilityService a = RemoteAccessibilityService.instance;
        if (a == null) return NEED_A11Y;
        boolean ok;
        switch (k) {
            case "back": ok = a.global(AccessibilityService.GLOBAL_ACTION_BACK); break;
            case "recents": ok = a.global(AccessibilityService.GLOBAL_ACTION_RECENTS); break;
            case "ok": ok = a.click(); break;
            case "up": case "down": case "left": case "right":
                ok = a.move(RemoteAccessibilityService.direction(k));
                break;
            case "menu": ok = a.global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS); break;
            default: return "Kekunci tak dikenali";
        }
        return ok ? null : "Tiada kesan pada apl ini";
    }

    private static int code(String k) {
        switch (k) {
            case "up": return KeyEvent.KEYCODE_DPAD_UP;
            case "down": return KeyEvent.KEYCODE_DPAD_DOWN;
            case "left": return KeyEvent.KEYCODE_DPAD_LEFT;
            case "right": return KeyEvent.KEYCODE_DPAD_RIGHT;
            case "ok": return KeyEvent.KEYCODE_DPAD_CENTER;
            case "back": return KeyEvent.KEYCODE_BACK;
            case "menu": return KeyEvent.KEYCODE_MENU;
            case "chup": return KeyEvent.KEYCODE_CHANNEL_UP;
            case "chdown": return KeyEvent.KEYCODE_CHANNEL_DOWN;
            default: return 0;
        }
    }

    private static void media(AudioManager am, int code) {
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }

    static String launch(Context c, String pkg) {
        Intent i = c.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return "Apl tak dijumpai";
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            c.startActivity(i);
            return null;
        } catch (Exception e) {
            return "Gagal buka apl: " + e.getMessage();
        }
    }

    static String openUrl(Context c, String url) {
        if (!url.contains("://")) url = "https://" + url;
        try {
            c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return null;
        } catch (Exception e) {
            return "Tiada pelayar web untuk buka pautan";
        }
    }

    static String text(String text, boolean append) {
        RemoteAccessibilityService a = RemoteAccessibilityService.instance;
        if (a == null) return NEED_A11Y;
        return a.setText(text, append) ? null : "Tiada kotak teks yang aktif pada TV";
    }

    static String touch(String type, float x1, float y1, float x2, float y2) {
        RemoteAccessibilityService a = RemoteAccessibilityService.instance;
        if (a == null) return NEED_A11Y;
        boolean ok = "tap".equals(type) ? a.tap(x1, y1) : a.swipe(x1, y1, x2, y2, 250);
        return ok ? null : "Sentuhan tak disokong (perlu Android 7+)";
    }
}
