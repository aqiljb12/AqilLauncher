package com.aqil.launcher;

import android.app.Application;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Hub.init(this);
        // Simpan punca crash supaya boleh dipaparkan pada TV & telefon (Remote › Lagi › Laporan ralat).
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    String txt = new java.util.Date() + "  [" + t.getName() + "]\n" + sw;
                    for (String n : new String[]{"crash.txt", "last_crash.txt"}) {
                        try (FileOutputStream o = new FileOutputStream(new File(getFilesDir(), n))) {
                            o.write(txt.getBytes("UTF-8"));
                        }
                    }
                } catch (Throwable ignored) {
                }
                if (def != null) def.uncaughtException(t, e);
            }
        });
    }

    /** Baca & padam laporan crash baharu (null jika tiada). */
    static String takeCrash() {
        File f = new File(Hub.app.getFilesDir(), "crash.txt");
        if (!f.exists()) return null;
        String s = read(f);
        f.delete();
        return s;
    }

    static String lastCrash() {
        File f = new File(Hub.app.getFilesDir(), "last_crash.txt");
        return f.exists() ? read(f) : "";
    }

    private static String read(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 64 * 1024)];
            int n = in.read(b);
            return new String(b, 0, Math.max(0, n), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
