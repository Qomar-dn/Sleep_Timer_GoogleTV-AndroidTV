package com.devanka.sleepserver;

import android.app.Activity;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;

import java.util.Locale;

/**
 * MainActivity — Leanback-styled main screen for the Sleep Timer Server.
 *
 * Displays:
 *  - Server IP address (from WiFi)
 *  - Server port (8765)
 *  - Live countdown timer status
 *  - Start / Stop server button
 *
 * Polls the {@link SleepService} static fields every second to update the UI.
 */
public class MainActivity extends Activity {

    private static final int POLL_INTERVAL_MS = 1000;

    private TextView textIpAddress;
    private TextView textPort;
    private TextView textTimerStatus;
    private TextView textServiceStatus;
    private Button buttonToggle;

    /** Handler for periodic UI updates. */
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Runnable that polls service state and refreshes the UI every second. */
    private final Runnable uiUpdater = new Runnable() {
        @Override
        public void run() {
            updateUi();
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    // ──────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Bind views
        textIpAddress    = findViewById(R.id.text_ip_address);
        textPort         = findViewById(R.id.text_port);
        textTimerStatus  = findViewById(R.id.text_timer_status);
        textServiceStatus = findViewById(R.id.text_service_status);
        buttonToggle     = findViewById(R.id.button_toggle_service);

        // Show IP
        textIpAddress.setText(getWifiIpAddress());
        textPort.setText("8765");

        // Toggle button logic
        buttonToggle.setOnClickListener(v -> toggleService());

        // Auto-start the foreground service on launch
        startSleepService();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh IP in case network changed
        textIpAddress.setText(getWifiIpAddress());
        // Start polling
        handler.post(uiUpdater);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(uiUpdater);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Service control
    // ──────────────────────────────────────────────────────────────────────────

    /** Starts the SleepService as a foreground service. */
    private void startSleepService() {
        Intent intent = new Intent(this, SleepService.class);
        startForegroundService(intent);
    }

    /** Stops the SleepService. */
    private void stopSleepService() {
        Intent intent = new Intent(this, SleepService.class);
        stopService(intent);
    }

    /** Toggles the service on/off. */
    private void toggleService() {
        if (SleepService.isRunning) {
            stopSleepService();
        } else {
            startSleepService();
        }
        // Small delay so the service state has time to update before next poll
        handler.postDelayed(this::updateUi, 300);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // UI updates
    // ──────────────────────────────────────────────────────────────────────────

    /** Reads SleepService static state and updates all UI elements. */
    private void updateUi() {
        // Service status
        if (SleepService.isRunning) {
            textServiceStatus.setText(R.string.service_running);
            textServiceStatus.setTextColor(0xFF03DAC5); // teal
            buttonToggle.setText(R.string.button_stop_server);
        } else {
            textServiceStatus.setText(R.string.service_stopped);
            textServiceStatus.setTextColor(0xFFCF6679); // error pink
            buttonToggle.setText(R.string.button_start_server);
        }

        // Timer status
        if (SleepService.isTimerActive) {
            int remaining = SleepService.remainingSeconds;
            int minutes = remaining / 60;
            int seconds = remaining % 60;
            textTimerStatus.setText(String.format(Locale.US, "%02d:%02d", minutes, seconds));
            textTimerStatus.setTextColor(0xFF03DAC5); // teal
        } else {
            textTimerStatus.setText(R.string.timer_inactive);
            textTimerStatus.setTextColor(0xFF9E9E9E); // grey
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Network helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Returns the device's WiFi IP address as a human-readable string.
     * Falls back to "No WiFi" if unavailable.
     */
    private String getWifiIpAddress() {
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext()
                    .getSystemService(WIFI_SERVICE);
            if (wifiManager == null) return "No WiFi";

            int ipInt = wifiManager.getConnectionInfo().getIpAddress();
            if (ipInt == 0) return "No WiFi";

            return String.format(Locale.US, "%d.%d.%d.%d",
                    (ipInt & 0xff),
                    (ipInt >> 8 & 0xff),
                    (ipInt >> 16 & 0xff),
                    (ipInt >> 24 & 0xff));
        } catch (Exception e) {
            return "Unknown";
        }
    }
}
