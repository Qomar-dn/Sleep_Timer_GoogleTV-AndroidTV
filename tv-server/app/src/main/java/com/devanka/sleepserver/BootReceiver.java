package com.devanka.sleepserver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * BootReceiver — Starts the {@link SleepService} on device boot, standby resume,
 * fast boot, TV program initialization, and watchdog alarms so the HTTP server
 * is ALWAYS available without needing to open the app manually.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : "UNKNOWN";
        Log.i(TAG, "Received trigger broadcast action: " + action);

        try {
            Intent serviceIntent = new Intent(context, SleepService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            Log.i(TAG, "Successfully requested SleepService start from action: " + action);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start SleepService from broadcast action: " + action, e);
        }
    }
}
