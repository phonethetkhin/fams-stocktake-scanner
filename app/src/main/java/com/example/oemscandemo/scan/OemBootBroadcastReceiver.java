package com.example.oemscandemo.scan;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.example.oemscandemo.barcode.Preference;
import com.example.oemscandemo.utils.MyApplication;

public class OemBootBroadcastReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            if (Preference.getScanSelfopenSupport(MyApplication.getContext(), true)) {
                Intent service = new Intent(context, OemScanService.class);
                context.startService(service);
            }
        }
    }
}
