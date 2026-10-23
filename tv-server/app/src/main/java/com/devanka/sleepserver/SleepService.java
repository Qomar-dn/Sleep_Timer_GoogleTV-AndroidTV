package com.devanka.sleepserver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.CountDownTimer;
import android.os.IBinder;
import android.util.Log;

import java.util.Locale;

/**
 * SleepService — Foreground service that hosts the HTTP server and manages
 * the sleep countdown timer.
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li>Starts {@link MiniHttpServer} on port 8765.</li>
 *   <li>Maintains a {@link CountDownTimer} that ticks every second.</li>
 *   <li>When the timer expires, calls {@link DevicePolicyManager#lockNow()}
 *       to put the TV to sleep.</li>
 *   <li>Shows a persistent foreground notification with the remaining time.</li>
 *   <li>Exposes static fields so {@link MainActivity} can read state without binding.</li>
 * </ul>
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

    /** Whether a countdown timer is currently active. */
    public static volatile boolean isTimerActive = false;

    /** Seconds remaining on the current timer (0 when inactive). */
    public static volatile int remainingSeconds = 0;

    /** Total seconds the timer was originally set for. */
    public static volatile int totalSeconds = 0;

    // ── Instance fields ──────────────────────────────────────────────────────
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private MiniHttpServer httpServer;
    private CountDownTimer countDownTimer;
    private NotificationManager notificationManager;
    private FloatingTimerOverlay floatingOverlay;

    // ──────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service created");

        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel();

        // Initialize floating HUD overlay
        floatingOverlay = new FloatingTimerOverlay(this);

        // Start HTTP server
        httpServer = new MiniHttpServer(PORT, this);
        httpServer.start();

        // Move to foreground immediately
        startForeground(NOTIFICATION_ID, buildNotification("Server running — no timer set"));
        isRunning = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Already running — nothing extra to do
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Service destroyed");
        isRunning = false;

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

        // Cancel timer on main thread
        mainHandler.post(this::cancelTimerInternal);

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        // Not a bound service
        return null;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Timer management (called from MiniHttpServer request handlers)
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

    /** Internal cancel without notification update (used by onDestroy). */
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
     * Returns the current status as a JSON string.
     */
    public String getStatusJson() {
        return String.format(Locale.US,
                "{\"active\":%b,\"remaining_seconds\":%d,\"total_seconds\":%d,\"server\":\"Devanka TV\"}",
                isTimerActive, remainingSeconds, totalSeconds);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Device sleep
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Uses DevicePolicyManager.lockNow() to turn the screen off / put the TV
     * to sleep. Requires the app to be registered as a Device Admin.
     */
    private void putTvToSleep() {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager)
                    getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponent =
                    new ComponentName(this, SleepDeviceAdmin.class);

            if (dpm != null && dpm.isAdminActive(adminComponent)) {
                dpm.lockNow();
                Log.i(TAG, "lockNow() called successfully");
            } else {
                Log.w(TAG, "Device admin not active — cannot lock screen. " +
                        "Please enable Device Admin for this app in Settings.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to lock device", e);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Notification helpers
    // ──────────────────────────────────────────────────────────────────────────

    /** Creates the notification channel (required on API 26+). */
    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW  // low = no sound
        );
        channel.setDescription(getString(R.string.notification_channel_desc));
        notificationManager.createNotificationChannel(channel);
    }

    /** Builds a foreground notification with the given content text. */
    private Notification buildNotification(String contentText) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    /** Updates the foreground notification with current timer state. */
    private void updateNotification() {
        String text;
        if (isTimerActive) {
            int min = remainingSeconds / 60;
            int sec = remainingSeconds % 60;
            String timeStr = String.format(Locale.US, "%02d:%02d", min, sec);
            text = String.format(getString(R.string.notification_countdown), timeStr);
        } else {
            text = getString(R.string.notification_idle);
        }
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text));
    }
}
