package com.devanka.sleepserver;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * SleepService — Persistent Foreground Service that hosts the HTTP server,
 * manages the sleep countdown timer, and automatically recovers when the TV wakes
 * from standby, reboots, or changes network connection.
 */
public class SleepService extends Service {

    private static final String TAG = "SleepService";

    /** HTTP server port. */
    public static final int PORT = 8765;

    /** Notification channel ID. */
    private static final String CHANNEL_ID = "sleep_timer";

    /** Foreground notification ID. */
    private static final int NOTIFICATION_ID = 1;

    // ── Static state readable by MainActivity ────────────────────────────────
    /** Whether this service is currently running. */
    public static volatile boolean isRunning = false;

    /** Whether the user manually stopped the service via UI. */
    public static volatile boolean isExplicitlyStopped = false;

    /** Whether a countdown timer is currently active. */
    public static volatile boolean isTimerActive = false;

    /** Seconds remaining on the current timer (0 when inactive). */
    public static volatile int remainingSeconds = 0;

    /** Total seconds the timer was originally set for. */
    public static volatile int totalSeconds = 0;

    // ── Instance fields ──────────────────────────────────────────────────────
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private MiniHttpServer httpServer;
    private CountDownTimer countDownTimer;
    private NotificationManager notificationManager;
    private FloatingTimerOverlay floatingOverlay;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private BroadcastReceiver screenReceiver;
    private ConnectivityManager.NetworkCallback networkCallback;

    // ──────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "SleepService created");

        isExplicitlyStopped = false;
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel();

        // 1. Acquire Partial WakeLock to keep CPU alive in background
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DevankaSleepServer:WakeLock");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
                Log.i(TAG, "WakeLock acquired");
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not acquire WakeLock", e);
        }

        // 2. Acquire High-Performance WifiLock so Wi-Fi stays responsive
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DevankaSleepServer:WifiLock");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
                Log.i(TAG, "WifiLock acquired");
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not acquire WifiLock", e);
        }

        // 3. Initialize floating HUD overlay
        floatingOverlay = new FloatingTimerOverlay(this);

        // 4. Start HTTP server
        startHttpServerSafely();

        // 5. Register dynamic Screen/Standby receiver
        registerScreenStateReceiver();

        // 6. Register Network callback
        registerNetworkCallback();

        // 7. Move to foreground immediately
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.service_running)));
        isRunning = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand called (startId=" + startId + ")");
        isExplicitlyStopped = false;

        // Ensure HTTP server is active
        startHttpServerSafely();

        // Make sure foreground notification is posted
        startForeground(NOTIFICATION_ID, buildNotification(
                isTimerActive ? getFormattedTimeNotification() : getString(R.string.service_running)));
        isRunning = true;

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "SleepService onDestroy called (isExplicitlyStopped=" + isExplicitlyStopped + ")");
        isRunning = false;

        // Clean up receivers and callbacks
        unregisterScreenStateReceiver();
        unregisterNetworkCallback();

        // Stop HTTP server
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }

        // Clean up floating HUD
        if (floatingOverlay != null) {
            floatingOverlay.destroy();
            floatingOverlay = null;
        }

        // Cancel timer
        mainHandler.post(this::cancelTimerInternal);

        // Release locks
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            try { wifiLock.release(); } catch (Exception ignored) {}
        }

        // If the system killed this service (not user pressing Stop Server in UI),
        // schedule an immediate restart watchdog via AlarmManager
        if (!isExplicitlyStopped) {
            scheduleWatchdogRestart();
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Resilient HTTP Server & Network Monitoring
    // ──────────────────────────────────────────────────────────────────────────

    /** Starts or verifies the HTTP server is alive and listening. */
    private synchronized void startHttpServerSafely() {
        if (httpServer == null || !httpServer.isListening()) {
            Log.i(TAG, "Starting MiniHttpServer on port " + PORT);
            if (httpServer != null) {
                httpServer.stop();
            }
            httpServer = new MiniHttpServer(PORT, this);
            httpServer.start();
        }
    }

    /** Registers a listener for Screen On / Off / Wakeup events. */
    private void registerScreenStateReceiver() {
        if (screenReceiver == null) {
            screenReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent != null ? intent.getAction() : "";
                    Log.i(TAG, "Screen state changed: " + action);
                    if (Intent.ACTION_SCREEN_ON.equals(action) || Intent.ACTION_USER_PRESENT.equals(action)) {
                        // TV screen turned on / resumed from standby
                        startHttpServerSafely();
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            registerReceiver(screenReceiver, filter);
        }
    }

    private void unregisterScreenStateReceiver() {
        if (screenReceiver != null) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (Exception ignored) {}
            screenReceiver = null;
        }
    }

    /** Registers a NetworkCallback to detect Wi-Fi / Ethernet state changes. */
    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                NetworkRequest request = new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build();

                networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        Log.i(TAG, "Network available — ensuring HTTP server is listening");
                        mainHandler.post(() -> startHttpServerSafely());
                    }

                    @Override
                    public void onLost(Network network) {
                        Log.w(TAG, "Network lost");
                    }
                };
                cm.registerNetworkCallback(request, networkCallback);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not register NetworkCallback", e);
        }
    }

    private void unregisterNetworkCallback() {
        if (networkCallback != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    cm.unregisterNetworkCallback(networkCallback);
                }
            } catch (Exception ignored) {}
            networkCallback = null;
        }
    }

    /** Schedules AlarmManager to revive the service if killed by system LMK. */
    private void scheduleWatchdogRestart() {
        try {
            Intent restartIntent = new Intent(this, BootReceiver.class);
            restartIntent.setAction("com.devanka.sleepserver.RESTART_SERVICE");
            PendingIntent pi = PendingIntent.getBroadcast(
                    this, 101, restartIntent,
                    PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE
            );
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                long triggerAt = System.currentTimeMillis() + 1500;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
                }
                Log.i(TAG, "Scheduled watchdog restart in 1.5s");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to schedule watchdog restart", e);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Timer management
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Sets (or resets) the sleep timer for the given number of minutes.
     *
     * @param minutes duration in minutes (must be > 0)
     */
    public synchronized void setTimer(int minutes) {
        totalSeconds = minutes * 60;
        remainingSeconds = totalSeconds;
        isTimerActive = true;

        Log.i(TAG, "Timer set for " + minutes + " minutes (" + totalSeconds + "s)");

        mainHandler.post(() -> {
            cancelTimerInternal();
            totalSeconds = minutes * 60;
            remainingSeconds = totalSeconds;
            isTimerActive = true;

            if (floatingOverlay != null) {
                floatingOverlay.show();
                floatingOverlay.update(remainingSeconds);
            }

            countDownTimer = new CountDownTimer(totalSeconds * 1000L, 1000L) {
                @Override
                public void onTick(long millisUntilFinished) {
                    remainingSeconds = (int) (millisUntilFinished / 1000);
                    updateNotification();
                    if (floatingOverlay != null) {
                        floatingOverlay.update(remainingSeconds);
                    }
                }

                @Override
                public void onFinish() {
                    Log.i(TAG, "Timer finished — putting TV to sleep");
                    remainingSeconds = 0;
                    isTimerActive = false;
                    updateNotification();
                    if (floatingOverlay != null) {
                        floatingOverlay.hide();
                    }
                    putTvToSleep();
                }
            };
            countDownTimer.start();
            updateNotification();
        });
    }

    /**
     * Cancels the active timer (if any).
     */
    public synchronized void cancelTimer() {
        isTimerActive = false;
        remainingSeconds = 0;
        totalSeconds = 0;
        mainHandler.post(() -> {
            cancelTimerInternal();
            updateNotification();
        });
    }

    /** Internal cancel without notification update. */
    private void cancelTimerInternal() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
            countDownTimer = null;
        }
        if (floatingOverlay != null) {
            floatingOverlay.hide();
        }
        isTimerActive = false;
        remainingSeconds = 0;
        totalSeconds = 0;
    }

    /**
     * Returns the current status as a JSON string, including TV MAC address.
     */
    public String getStatusJson() {
        String mac = getMacAddress();
        return String.format(Locale.US,
                "{\"active\":%b,\"remaining_seconds\":%d,\"total_seconds\":%d,\"server\":\"Devanka TV\",\"mac\":\"%s\"}",
                isTimerActive, remainingSeconds, totalSeconds, mac);
    }

    /**
     * Queries the device's network hardware address (MAC).
     */
    public static String getMacAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                String name = intf.getName().toLowerCase(Locale.US);
                if (name.contains("wlan") || name.contains("eth")) {
                    byte[] mac = intf.getHardwareAddress();
                    if (mac != null && mac.length > 0) {
                        StringBuilder buf = new StringBuilder();
                        for (byte b : mac) {
                            buf.append(String.format("%02X:", b));
                        }
                        if (buf.length() > 0) {
                            buf.deleteCharAt(buf.length() - 1);
                        }
                        return buf.toString();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Device sleep
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Uses DevicePolicyManager.lockNow() to turn the screen off / put the TV to sleep.
     */
    private void putTvToSleep() {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponent = new ComponentName(this, SleepDeviceAdmin.class);

            if (dpm != null && dpm.isAdminActive(adminComponent)) {
                dpm.lockNow();
                Log.i(TAG, "lockNow() called successfully");
            } else {
                Log.w(TAG, "Device admin not active — cannot lock screen.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to lock device", e);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Notification helpers
    // ──────────────────────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.notification_channel_desc));
            notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String contentText) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private String getFormattedTimeNotification() {
        int min = remainingSeconds / 60;
        int sec = remainingSeconds % 60;
        String timeStr = String.format(Locale.US, "%02d:%02d", min, sec);
        return String.format(getString(R.string.notification_countdown), timeStr);
    }

    private void updateNotification() {
        String text = isTimerActive ? getFormattedTimeNotification() : getString(R.string.notification_idle);
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text));
    }
}
