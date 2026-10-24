package com.devanka.sleepremote;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.devanka.sleepremote.databinding.ActivityMainBinding;
import com.google.android.material.button.MaterialButton;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main activity for the Sleep Timer Remote app (v2.0).
 * Provides multi-TV management (TV 1 to 5), Wake-on-LAN, gaming-themed UI,
 * in-app user guide, and developer about dialog.
 */
public class MainActivity extends AppCompatActivity {

    private static final long POLL_INTERVAL_MS = 1000;

    private ActivityMainBinding binding;
    private TvDeviceManager deviceManager;
    private final TvApiClient apiClient = new TvApiClient();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private String currentIp = "";
    private boolean isConnected = false;
    private boolean isPolling = false;

    private MaterialButton[] tvChips;

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

        deviceManager = new TvDeviceManager(this);

        setupTvChips();
        setupClickListeners();
        selectTv(deviceManager.getSelectedIndex(), false);
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

    // ──────────────────────────────────────────────────────────────────────────
    // Multi-TV Management (TV 1 - 5)
    // ──────────────────────────────────────────────────────────────────────────

    private void setupTvChips() {
        tvChips = new MaterialButton[]{
                binding.chipTv1,
                binding.chipTv2,
                binding.chipTv3,
                binding.chipTv4,
                binding.chipTv5
        };

        for (int i = 0; i < tvChips.length; i++) {
            final int index = i;
            tvChips[i].setOnClickListener(v -> selectTv(index, true));
        }

        binding.btnEditTv.setOnClickListener(v -> showEditTvDialog(deviceManager.getSelectedIndex()));
    }

    /** Selects a TV slot (0 to 4) and updates the UI accordingly. */
    private void selectTv(int index, boolean attemptAutoConnect) {
        if (isConnected) {
            stopPolling();
            isConnected = false;
            updateConnectionUI(false);
        }

        deviceManager.setSelectedIndex(index);
        List<TvDevice> list = deviceManager.getDevices();
        TvDevice currentDevice = deviceManager.getSelectedDevice();

        // Update chips styling and labels
        for (int i = 0; i < tvChips.length; i++) {
            if (i < list.size()) {
                tvChips[i].setText("TV " + list.get(i).getId());
            }
            if (i == index) {
                tvChips[i].setBackgroundResource(R.drawable.bg_tv_chip_selected);
                tvChips[i].setTextColor(getColor(R.color.text_primary));
            } else {
                tvChips[i].setBackgroundResource(R.drawable.bg_tv_chip_unselected);
                tvChips[i].setTextColor(getColor(R.color.text_secondary));
            }
        }

        if (currentDevice != null) {
            binding.textCurrentTvBadge.setText(String.format("Aktif: %s", currentDevice.getName()));
            binding.editIpAddress.setText(currentDevice.getIp());
            currentIp = currentDevice.getIp();

            if (attemptAutoConnect && !TextUtils.isEmpty(currentIp)) {
                onConnectClicked();
            }
        }
    }

    /** Shows the dialog to edit TV Name, IP address, and MAC address. */
    private void showEditTvDialog(int slotIndex) {
        final TvDevice device = deviceManager.getDevices().get(slotIndex);
        if (device == null) return;

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_edit_tv);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        TextView title = dialog.findViewById(R.id.dialogTvTitle);
        EditText editName = dialog.findViewById(R.id.editTvName);
        EditText editIp = dialog.findViewById(R.id.editTvIp);
        EditText editMac = dialog.findViewById(R.id.editTvMac);
        MaterialButton btnCancel = dialog.findViewById(R.id.btnCancelEdit);
        MaterialButton btnSave = dialog.findViewById(R.id.btnSaveEdit);

        title.setText("Atur " + device.getName());
        editName.setText(device.getName());
        editIp.setText(device.getIp());
        editMac.setText(device.getMac());

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnSave.setOnClickListener(v -> {
            String newName = editName.getText().toString().trim();
            String newIp = editIp.getText().toString().trim();
            String newMac = editMac.getText().toString().trim();

            if (TextUtils.isEmpty(newName)) {
                newName = "TV " + device.getId();
            }
            if (TextUtils.isEmpty(newIp)) {
                Toast.makeText(MainActivity.this, "Alamat IP tidak boleh kosong", Toast.LENGTH_SHORT).show();
                return;
            }

            deviceManager.updateDevice(slotIndex, newName, newIp, newMac);
            selectTv(slotIndex, false);
            Toast.makeText(MainActivity.this, "Pengaturan " + newName + " berhasil disimpan", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        dialog.show();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Click Listeners
    // ──────────────────────────────────────────────────────────────────────────

    private void setupClickListeners() {
        binding.btnConnect.setOnClickListener(v -> onConnectClicked());

        // Guide and About Dialogs
        binding.btnGuide.setOnClickListener(v -> showUserGuideDialog());
        binding.btnAbout.setOnClickListener(v -> showAboutDialog());

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

    /** Shows the User Guide (Cara Penggunaan) Dialog. */
    private void showUserGuideDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_user_guide);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        MaterialButton btnClose = dialog.findViewById(R.id.btnCloseGuide);
        btnClose.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    /** Shows the About Dialog (Tentang Aplikasi & Pengembang). */
    private void showAboutDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_about);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        View btnInstagram = dialog.findViewById(R.id.btnInstagram);
        if (btnInstagram != null) {
            btnInstagram.setOnClickListener(v -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/qomar.dn_"));
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Instagram: @qomar.dn_", Toast.LENGTH_SHORT).show();
                }
            });
        }

        View btnThreads = dialog.findViewById(R.id.btnThreads);
        if (btnThreads != null) {
            btnThreads.setOnClickListener(v -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.threads.net/@qomar.dn_"));
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Threads: @qomar.dn_", Toast.LENGTH_SHORT).show();
                }
            });
        }

        MaterialButton btnClose = dialog.findViewById(R.id.btnCloseAbout);
        btnClose.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    /** Adjusts the number in editCustomMinutes by delta (e.g. +15 or -15). */
    private void adjustCustomMinutes(int delta) {
        String text = binding.editCustomMinutes.getText().toString().trim();
        int current = 30;
        if (!TextUtils.isEmpty(text)) {
            try {
                current = Integer.parseInt(text);
            } catch (NumberFormatException ignored) {}
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

    /** Connects to the active TV with automatic Wake-on-LAN fallback. */
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
        TvDevice device = deviceManager.getSelectedDevice();
        if (device != null) {
            device.setIp(ip);
            deviceManager.saveDevices();
        }

        binding.btnConnect.setEnabled(false);

        // Attempt to connect by fetching status
        executor.execute(() -> {
            TvApiClient.StatusResponse response = apiClient.getStatus(currentIp);

            // If first attempt failed, check if we have a saved MAC address to wake the TV
            if (response == null) {
                String savedMac = device != null ? device.getMac() : "";
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
                    if (!TextUtils.isEmpty(finalResponse.macAddress) && device != null) {
                        device.setMac(finalResponse.macAddress);
                        deviceManager.saveDevices();
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

    /** Updates the connection status indicator (dot color and text). */
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

    /** Updates the timer display based on the server response. */
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
