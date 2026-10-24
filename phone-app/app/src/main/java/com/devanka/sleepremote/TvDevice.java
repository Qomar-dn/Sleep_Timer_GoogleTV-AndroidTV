package com.devanka.sleepremote;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Model representing a managed TV device (Slot 1 to 5).
 */
public class TvDevice {

    private int id; // 1 to 5
    private String name;
    private String ip;
    private String mac;
    private boolean isConnected;
    private boolean isTimerActive;
    private int remainingSeconds;
    private int totalSeconds;

    public TvDevice(int id, String name, String ip, String mac) {
        this.id = id;
        this.name = name;
        this.ip = ip;
        this.mac = mac;
        this.isConnected = false;
        this.isTimerActive = false;
        this.remainingSeconds = 0;
        this.totalSeconds = 0;
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getMac() {
        return mac;
    }

    public void setMac(String mac) {
        this.mac = mac;
    }

    public boolean isConnected() {
        return isConnected;
    }

    public void setConnected(boolean connected) {
        isConnected = connected;
    }

    public boolean isTimerActive() {
        return isTimerActive;
    }

    public void setTimerActive(boolean timerActive) {
        isTimerActive = timerActive;
    }

    public int getRemainingSeconds() {
        return remainingSeconds;
    }

    public void setRemainingSeconds(int remainingSeconds) {
        this.remainingSeconds = remainingSeconds;
    }

    public int getTotalSeconds() {
        return totalSeconds;
    }

    public void setTotalSeconds(int totalSeconds) {
        this.totalSeconds = totalSeconds;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", id);
        obj.put("name", name);
        obj.put("ip", ip);
        obj.put("mac", mac);
        return obj;
    }

    public static TvDevice fromJson(JSONObject obj) {
        int id = obj.optInt("id", 1);
        String name = obj.optString("name", "TV " + id);
        String ip = obj.optString("ip", "192.168.1." + (77 + id));
        String mac = obj.optString("mac", "");
        return new TvDevice(id, name, ip, mac);
    }
}
