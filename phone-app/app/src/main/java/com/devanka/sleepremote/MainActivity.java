package com.devanka.sleepremote;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.devanka.sleepremote.databinding.ActivityMainBinding;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main activity for the Sleep Timer Remote app.
 * Provides a gaming-themed dark UI to control a TV sleep timer over HTTP.
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "sleep_remote_prefs";
    private static final String KEY_LAST_IP = "last_ip";
    private static final String KEY_LAST_MAC = "last_mac";
    private static final String DEFAULT_IP = "192.168.1.78";
    private static final long POLL_INTERVAL_MS = 1000;

    private ActivityMainBinding binding;
    private final TvApiClient apiClient = new TvApiClient();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private String currentIp = "";
    private boolean isConnected = false;
    private boolean isPolling = false;

    /** Runnable that periodically polls the TV server for status. */
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isPolling) return;
            pollStatus();
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        loadSavedIp();
        setupClickListeners();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isConnected) {
            startPolling();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPolling();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPolling();
        executor.shutdown();
    }

    /**
     * Loads the last-used IP address from SharedPreferences and populates
     * the IP EditText. If no saved IP, focuses the field for input.
     */
    private void loadSavedIp() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedIp = prefs.getString(KEY_LAST_IP, "");
        if (TextUtils.isEmpty(savedIp)) {
            binding.editIpAddress.setText(DEFAULT_IP);
            binding.editIpAddress.requestFocus();
            binding.editIpAddress.selectAll();
        } else {
            binding.editIpAddress.setText(savedIp);
        }
    }

    /**
     * Saves the given IP address to SharedPreferences for next launch.
     *
     * @param ip The IP address to persist.
     */
    private void saveIp(String ip) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_IP, ip)
                .apply();
    }

    /** Sets up all button click listeners. */
    private void setupClickListeners() {
        binding.btnConnect.setOnClickListener(v -> onConnectClicked());

        // Quick Presets
        binding.btn15.setOnClickListener(v -> setTimer(15));
        binding.btn30.setOnClickListener(v -> setTimer(30));
        binding.btn45.setOnClickListener(v -> setTimer(45));
        binding.btn60.setOnClickListener(v -> setTimer(60));   // 1 Jam
        binding.btn120.setOnClickListener(v -> setTimer(120)); // 2 Jam
        binding.btn180.setOnClickListener(v -> setTimer(180)); // 3 Jam

        // Custom Steppers (-15 / +15)
        binding.btnMinus.setOnClickListener(v -> adjustCustomMinutes(-15));
        binding.btnPlus.setOnClickListener(v -> adjustCustomMinutes(15));

        // Custom Set Button
        binding.btnSetCustom.setOnClickListener(v -> onCustomSetClicked());

        // Cancel Button
        binding.btnCancel.setOnClickListener(v -> cancelTimer());
    }

    /** Adjusts the number in editCustomMinutes by delta (e.g. +15 or -15). */
    private void adjustCustomMinutes(int delta) {
        String text = binding.editCustomMinutes.getText().toString().trim();
        int current = 30;
        if (!TextUtils.isEmpty(text)) {
            try {
                current = Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
            }
        }
        int next = current + delta;
        if (next < 1) next = 1;
        if (next > 1440) next = 1440;
        binding.editCustomMinutes.setText(String.valueOf(next));
        binding.editCustomMinutes.setSelection(binding.editCustomMinutes.getText().length());
    }

    /** Handles clicking the custom set button. */
    private void onCustomSetClicked() {
        String text = binding.editCustomMinutes.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            Toast.makeText(this, getString(R.string.invalid_minutes), Toast.LENGTH_SHORT).show();
            binding.editCustomMinutes.requestFocus();
            return;
        }

        try {
            int minutes = Integer.parseInt(text);
            if (minutes <= 0 || minutes > 1440) {
                Toast.makeText(this, getString(R.string.invalid_minutes), Toast.LENGTH_SHORT).show();
                return;
            }
            setTimer(minutes);
        } catch (NumberFormatException e) {
            Toast.makeText(this, getString(R.string.invalid_minutes), Toast.LENGTH_SHORT).show();
        }
    }

    /** Called when the Connect button is tapped. Validates IP and attempts connection. */
    private void onConnectClicked() {
        String ip = binding.editIpAddress.getText().toString().trim();
        if (TextUtils.isEmpty(ip)) {
            Toast.makeText(this, getString(R.string.server_label), Toast.LENGTH_SHORT).show();
            return;
        }

        if (isConnected && ip.equals(currentIp)) {
            // Disconnect
            stopPolling();
            isConnected = false;
            updateConnectionUI(false);
            return;
        }

        currentIp = ip;
        saveIp(ip);
        binding.btnConnect.setEnabled(false);

        // Attempt to connect by fetching status with automatic Wake-on-LAN recovery
        executor.execute(() -> {
            TvApiClient.StatusResponse response = apiClient.getStatus(currentIp);

            // If first attempt failed, check if we have a saved MAC address to wake the TV
            if (response == null) {
                String savedMac = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_LAST_MAC, "");
                if (!TextUtils.isEmpty(savedMac)) {
                    handler.post(() -> Toast.makeText(MainActivity.this,
                            "TV belum merespons. Mengirim sinyal bangun (Wake-on-LAN)...", Toast.LENGTH_SHORT).show());
                    WakeOnLan.sendWakeOnLan(currentIp, savedMac);
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {}
                    response = apiClient.getStatus(currentIp);
                }
            }

            final TvApiClient.StatusResponse finalResponse = response;
            handler.post(() -> {
                binding.btnConnect.setEnabled(true);
                if (finalResponse != null) {
                    isConnected = true;
                    if (!TextUtils.isEmpty(finalResponse.macAddress)) {
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                                .edit()
                                .putString(KEY_LAST_MAC, finalResponse.macAddress)
                                .apply();
                    }
                    updateConnectionUI(true);
                    updateTimerUI(finalResponse);
                    startPolling();
                } else {
                    isConnected = false;
                    updateConnectionUI(false);
                    Toast.makeText(MainActivity.this,
                            getString(R.string.connection_error), Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    /**
     * Sends a set-timer request to the TV server.
     *
     * @param minutes The number of minutes for the sleep timer.
     */
    private void setTimer(int minutes) {
        if (!isConnected) {
            Toast.makeText(this, getString(R.string.connection_error), Toast.LENGTH_SHORT).show();
            return;
        }

        executor.execute(() -> {
            TvApiClient.StatusResponse response = apiClient.setTimer(currentIp, minutes);
            handler.post(() -> {
                if (response != null) {
                    updateTimerUI(response);
                    String msg;
                    if (minutes >= 60 && minutes % 60 == 0) {
                        msg = String.format(Locale.getDefault(), "Timer diatur %d Jam", minutes / 60);
                    } else {
                        msg = String.format(Locale.getDefault(), getString(R.string.timer_set), minutes);
                    }
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MainActivity.this,
                            getString(R.string.connection_error), Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    /** Sends a cancel-timer request to the TV server. */
    private void cancelTimer() {
        if (!isConnected) {
            Toast.makeText(this, getString(R.string.connection_error), Toast.LENGTH_SHORT).show();
            return;
        }

        executor.execute(() -> {
            TvApiClient.StatusResponse response = apiClient.cancelTimer(currentIp);
            handler.post(() -> {
                if (response != null) {
                    updateTimerUI(response);
                    Toast.makeText(MainActivity.this,
                            getString(R.string.timer_cancelled), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MainActivity.this,
                            getString(R.string.connection_error), Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    /** Polls the TV server for the current timer status. */
    private void pollStatus() {
        executor.execute(() -> {
            TvApiClient.StatusResponse response = apiClient.getStatus(currentIp);
            handler.post(() -> {
                if (response != null) {
                    if (!isConnected) {
                        isConnected = true;
                        updateConnectionUI(true);
                    }
                    updateTimerUI(response);
                } else {
                    if (isConnected) {
                        isConnected = false;
                        updateConnectionUI(false);
                    }
                }
            });
        });
    }

    /** Starts the periodic status polling. */
    private void startPolling() {
        if (isPolling) return;
        isPolling = true;
        handler.post(pollRunnable);
    }

    /** Stops the periodic status polling. */
    private void stopPolling() {
        isPolling = false;
        handler.removeCallbacks(pollRunnable);
    }

    /**
     * Updates the connection status indicator (dot color and text).
     *
     * @param connected Whether the app is currently connected to the TV server.
     */
    private void updateConnectionUI(boolean connected) {
        if (connected) {
            binding.viewStatusDot.setBackgroundResource(R.drawable.status_dot_connected);
            binding.textConnectionStatus.setText(R.string.connected);
            binding.textConnectionStatus.setTextColor(getColor(R.color.status_connected));
            binding.btnConnect.setText(R.string.disconnected);
        } else {
            binding.viewStatusDot.setBackgroundResource(R.drawable.status_dot_disconnected);
            binding.textConnectionStatus.setText(R.string.disconnected);
            binding.textConnectionStatus.setTextColor(getColor(R.color.status_disconnected));
            binding.btnConnect.setText(R.string.connect);
            resetTimerUI();
        }
    }

    /**
     * Updates the timer display based on the server response.
     *
     * @param response The status response from the TV server.
     */
    private void updateTimerUI(TvApiClient.StatusResponse response) {
        binding.circularTimerView.setMaxSeconds(response.totalSeconds);
        binding.circularTimerView.setRemainingSeconds(response.remainingSeconds);

        if (response.active && response.remainingSeconds > 0) {
            int hours = response.remainingSeconds / 3600;
            int minutes = (response.remainingSeconds % 3600) / 60;
            int seconds = response.remainingSeconds % 60;

            if (hours > 0) {
                binding.textRemainingTime.setText(
                        String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds));
            } else {
                binding.textRemainingTime.setText(
                        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds));
            }

            binding.textTimerStatus.setText(
                    String.format(Locale.getDefault(), "%s • %s",
                            getString(R.string.remaining), response.serverName));
            binding.textRemainingTime.setTextColor(getColor(R.color.accent_cyan));
        } else {
            binding.textRemainingTime.setText("00:00");
            binding.textTimerStatus.setText(R.string.no_timer);
            binding.textRemainingTime.setTextColor(getColor(R.color.text_secondary));
        }
    }

    /** Resets the timer UI to the default inactive state. */
    private void resetTimerUI() {
        binding.circularTimerView.setMaxSeconds(0);
        binding.circularTimerView.setRemainingSeconds(0);
        binding.textRemainingTime.setText("00:00");
        binding.textRemainingTime.setTextColor(getColor(R.color.text_secondary));
        binding.textTimerStatus.setText(R.string.no_timer);
    }
}
