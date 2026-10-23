package com.devanka.sleepserver;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * SleepDeviceAdmin — Minimal {@link DeviceAdminReceiver} that enables the
 * {@code force-lock} policy so the app can call
 * {@link android.app.admin.DevicePolicyManager#lockNow()}.
 *
 * <p>The user must manually enable this Device Admin in
 * <em>Settings → Security → Device admin apps</em> before the sleep
 * functionality will work.</p>
 */
public class SleepDeviceAdmin extends DeviceAdminReceiver {

    private static final String TAG = "SleepDeviceAdmin";

    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
        Log.i(TAG, "Device Admin enabled");
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
        Log.i(TAG, "Device Admin disabled");
    }

    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        return "Disabling will prevent the Sleep Timer from turning off the TV.";
    }
}
