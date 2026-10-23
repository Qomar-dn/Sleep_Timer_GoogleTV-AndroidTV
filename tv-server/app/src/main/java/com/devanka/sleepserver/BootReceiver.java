package com.devanka.sleepserver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * BootReceiver — Starts the {@link SleepService} when the device boots up,
 * so the HTTP server is always available after a reboot.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.i(TAG, "Boot completed — starting SleepService");
            Intent serviceIntent = new Intent(context, SleepService.class);
            context.startForegroundService(serviceIntent);
        }
    }
}
