package com.aqil.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i == null ? null : i.getAction();
        // hanya selepas but (BOOT_COMPLETED dibenarkan memulakan servis latar depan pada Android 8–14)
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || "android.intent.action.QUICKBOOT_POWERON".equals(a)) {
            RemoteService.start(c);
        }
    }
}
