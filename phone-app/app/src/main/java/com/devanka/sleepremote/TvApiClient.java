package com.devanka.sleepremote;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * HTTP client for communicating with the TV Sleep Timer server.
 * All methods are blocking and must be called from a background thread.
 */
public class TvApiClient {

    private static final int PORT = 8765;
    private static final int CONNECT_TIMEOUT = 3000;
    private static final int READ_TIMEOUT = 3000;

    /**
     * Represents the response from the TV server.
     */
    public static class StatusResponse {
        public boolean active;
        public int remainingSeconds;
        public int totalSeconds;
        public String serverName;

        public StatusResponse(boolean active, int remainingSeconds, int totalSeconds, String serverName) {
            this.active = active;
            this.remainingSeconds = remainingSeconds;
            this.totalSeconds = totalSeconds;
            this.serverName = serverName;
        }
    }

    /**
     * Fetches the current timer status from the TV server.
     *
     * @param ip The IP address of the TV server.
     * @return A StatusResponse if successful, or null on error.
     */
    public StatusResponse getStatus(String ip) {
        return executeRequest(ip, "/status", "GET");
    }

    /**
     * Sets the sleep timer on the TV server.
     *
     * @param ip      The IP address of the TV server.
     * @param minutes The number of minutes for the timer.
     * @return A StatusResponse if successful, or null on error.
     */
    public StatusResponse setTimer(String ip, int minutes) {
        return executeRequest(ip, "/set?minutes=" + minutes, "POST");
    }

    /**
     * Cancels the active sleep timer on the TV server.
     *
     * @param ip The IP address of the TV server.
     * @return A StatusResponse if successful, or null on error.
     */
    public StatusResponse cancelTimer(String ip) {
        return executeRequest(ip, "/cancel", "POST");
    }

    /**
     * Executes an HTTP request to the TV server and parses the JSON response.
     *
     * @param ip     The IP address of the TV server.
     * @param path   The URL path (e.g., "/status").
     * @param method The HTTP method ("GET" or "POST").
     * @return A StatusResponse if successful, or null on error.
     */
    private StatusResponse executeRequest(String ip, String path, String method) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("http://" + ip + ":" + PORT + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            connection.setDoInput(true);

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return null;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();

            return parseResponse(response.toString());
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Parses a JSON response string into a StatusResponse object.
     *
     * @param jsonString The raw JSON string from the server.
     * @return A StatusResponse if parsing succeeds, or null on error.
     */
    private StatusResponse parseResponse(String jsonString) {
        try {
            JSONObject json = new JSONObject(jsonString);
            boolean active = json.optBoolean("active", false);
            int remainingSeconds = json.optInt("remaining_seconds", 0);
            int totalSeconds = json.optInt("total_seconds", 0);
            String serverName = json.optString("server", "TV");
            return new StatusResponse(active, remainingSeconds, totalSeconds, serverName);
        } catch (Exception e) {
            return null;
        }
    }
}
