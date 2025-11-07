package com.obby.android.externalstoragemonitor.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

import com.obby.android.externalstoragemonitor.service.MonitorService;
import com.obby.android.externalstoragemonitor.support.Preferences;

public class BootCompletedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) && Preferences.get().isMonitorEnabled()) {
            ContextCompat.startForegroundService(context, new Intent(context, MonitorService.class));
        }
    }
}
