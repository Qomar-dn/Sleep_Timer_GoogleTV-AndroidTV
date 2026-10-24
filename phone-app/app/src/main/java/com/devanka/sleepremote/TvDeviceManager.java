package com.devanka.sleepremote;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages storage and selection of multiple TV devices (TV 1 to 5).
 */
public class TvDeviceManager {

    private static final String PREF_NAME = "devanka_tv_devices";
    private static final String KEY_DEVICES = "tv_list";
    private static final String KEY_SELECTED_INDEX = "selected_tv_index";
    public static final int MAX_TVS = 5;

    private final SharedPreferences prefs;
    private final List<TvDevice> devices = new ArrayList<>();
    private int selectedIndex = 0;

    public TvDeviceManager(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        loadDevices();
    }

    private void loadDevices() {
        devices.clear();
        String json = prefs.getString(KEY_DEVICES, null);
        selectedIndex = prefs.getInt(KEY_SELECTED_INDEX, 0);

        if (json != null) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    devices.add(TvDevice.fromJson(arr.getJSONObject(i)));
                }
            } catch (Exception ignored) {}
        }

        // Initialize default 5 TVs if empty or incomplete
        if (devices.isEmpty()) {
            devices.add(new TvDevice(1, "TV 1 (Coocaa)", "192.168.1.61", ""));
            devices.add(new TvDevice(2, "TV 2 (TCL)", "192.168.1.78", "84:3E:1D:47:A2:2F"));
            devices.add(new TvDevice(3, "TV 3", "192.168.1.79", ""));
            devices.add(new TvDevice(4, "TV 4", "192.168.1.80", ""));
            devices.add(new TvDevice(5, "TV 5", "192.168.1.81", ""));
            saveDevices();
        } else {
            // Ensure up to 5 slots exist
            while (devices.size() < MAX_TVS) {
                int nextId = devices.size() + 1;
                devices.add(new TvDevice(nextId, "TV " + nextId, "192.168.1." + (77 + nextId), ""));
            }
        }

        if (selectedIndex < 0 || selectedIndex >= devices.size()) {
            selectedIndex = 0;
        }
    }

    public synchronized void saveDevices() {
        try {
            JSONArray arr = new JSONArray();
            for (TvDevice device : devices) {
                arr.put(device.toJson());
            }
            prefs.edit()
                    .putString(KEY_DEVICES, arr.toString())
                    .putInt(KEY_SELECTED_INDEX, selectedIndex)
                    .apply();
        } catch (Exception ignored) {}
    }

    public List<TvDevice> getDevices() {
        return devices;
    }

    public TvDevice getSelectedDevice() {
        if (devices.isEmpty()) return null;
        if (selectedIndex >= devices.size()) selectedIndex = 0;
        return devices.get(selectedIndex);
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public void setSelectedIndex(int index) {
        if (index >= 0 && index < devices.size()) {
            this.selectedIndex = index;
            prefs.edit().putInt(KEY_SELECTED_INDEX, selectedIndex).apply();
        }
    }

    public void updateDevice(int index, String name, String ip, String mac) {
        if (index >= 0 && index < devices.size()) {
            TvDevice d = devices.get(index);
            d.setName(name);
            d.setIp(ip);
            if (mac != null && !mac.isEmpty()) {
                d.setMac(mac);
            }
            saveDevices();
        }
    }
}
