package com.devanka.sleepremote;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * WakeOnLan — Utility to send a Wake-on-LAN magic packet over UDP port 9.
 * Used when the TV is powered off / in deep standby so it wakes up and starts its services.
 */
public class WakeOnLan {

    private static final String TAG = "WakeOnLan";

    public static boolean sendWakeOnLan(String targetIp, String macAddress) {
        if (macAddress == null || macAddress.trim().isEmpty()) {
            return false;
        }

        try {
            byte[] macBytes = getMacBytes(macAddress.trim());
            byte[] bytes = new byte[6 + 16 * macBytes.length];
            for (int i = 0; i < 6; i++) {
                bytes[i] = (byte) 0xff;
            }
            for (int i = 6; i < bytes.length; i += macBytes.length) {
                System.arraycopy(macBytes, 0, bytes, i, macBytes.length);
            }

            // Determine broadcast IP: replace last octet with 255
            String broadcastIp = "255.255.255.255";
            if (targetIp != null && targetIp.contains(".")) {
                int lastDot = targetIp.lastIndexOf('.');
                if (lastDot > 0) {
                    broadcastIp = targetIp.substring(0, lastDot) + ".255";
                }
            }

            InetAddress address = InetAddress.getByName(broadcastIp);
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length, address, 9);
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setBroadcast(true);
                socket.send(packet);
            }

            Log.i(TAG, "Sent Wake-on-LAN packet to " + macAddress + " via " + broadcastIp);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to send Wake-on-LAN packet", e);
            return false;
        }
    }

    private static byte[] getMacBytes(String macStr) throws IllegalArgumentException {
        String[] hex = macStr.split("(\\:|\\-)");
        if (hex.length != 6) {
            throw new IllegalArgumentException("Invalid MAC address: " + macStr);
        }
        byte[] bytes = new byte[6];
        for (int i = 0; i < 6; i++) {
            bytes[i] = (byte) Integer.parseInt(hex[i], 16);
        }
        return bytes;
    }
}
