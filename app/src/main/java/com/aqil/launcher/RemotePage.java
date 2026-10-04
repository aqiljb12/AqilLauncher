package com.aqil.launcher;

import android.content.Intent;
import android.graphics.Bitmap;
import android.provider.Settings;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.EnumMap;
import java.util.Map;

/** Cara sambung telefon: kod QR, alamat, PIN, status kebolehcapaian. */
final class RemotePage extends Page {
    RemotePage(MainActivity a) {
        super(a);
    }

    @Override
    void build() {
        root.removeAllViews();
        String url = U.remoteUrl();
        FrameLayout qrBox = new FrameLayout(a);
        qrBox.setBackground(Ui.solid(0xFFFFFFFF, S.px(30)));
        qrBox.setElevation(S.px(10));
        ImageView qr = new ImageView(a);
        qr.setImageBitmap(qr(url, S.px(380)));
        qrBox.addView(qr, Ui.at(20, 20, 380, 380));
        root.addView(qrBox, Ui.at(40, 40, 420, 420));
        TextView scan = Ui.text(a, "Imbas dengan kamera telefon", 24, Ui.DIM, Ui.MEDIUM);
        root.addView(scan, Ui.at(80, 480, -2, -2));

        root.addView(Ui.text(a, "Kawal TV dengan telefon", 56, Ui.WHITE, Ui.BOLD), Ui.at(540, 40, -2, -2));
        TextView steps = Ui.text(a, "1.  Sambung telefon ke Wi-Fi yang sama dengan TV\n2.  Imbas kod QR, atau buka pelayar dan taip:", 28, Ui.DIM, Ui.MEDIUM);
        steps.setSingleLine(false);
        steps.setLineSpacing(S.px(10), 1f);
        root.addView(steps, Ui.at(544, 130, 1100, -2));
        root.addView(Ui.text(a, url, 54, 0xFF64B5FF, Ui.BOLD), Ui.at(544, 236, -2, -2));
        root.addView(Ui.text(a, "3.  Masukkan PIN:", 28, Ui.DIM, Ui.MEDIUM), Ui.at(544, 330, -2, -2));
        root.addView(Ui.text(a, Store.pin(a), 96, Ui.WHITE, Ui.BOLD), Ui.at(544, 370, -2, -2));

        boolean a11y = RemoteAccessibilityService.instance != null;
        Row acc = new Row(a, "Kawal apl lain (Netflix, YouTube…)", a11y ? "Aktif – Back, Home, sentuhan & taip berfungsi di semua apl"
                : "Tekan untuk hidupkan 'Aqil Launcher Remote' di Kebolehcapaian", a11y ? "Aktif ✓" : "Hidupkan");
        acc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.openSafe(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        root.addView(acc, Ui.at(40, 560, 1000, 100));
        firstView = acc;
        Row pin = new Row(a, "Tukar PIN", "Telefon lama perlu masukkan PIN baharu", null);
        pin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Store.newPin(a);
                Toast.makeText(a, "PIN baharu: " + Store.pin(a), Toast.LENGTH_LONG).show();
                build();
                if (firstView != null) firstView.requestFocus();
            }
        });
        root.addView(pin, Ui.at(1070, 560, 560, 100));
    }

    static Bitmap qr(String text, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 0);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            int w = m.getWidth(), h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) px[y * w + x] = m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }
    }
}
