package com.devanka.sleepserver;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * MiniHttpServer — A minimal, zero-dependency embedded HTTP server built on
 * {@link ServerSocket}. Designed to handle REST-style JSON requests for the
 * Sleep Timer TV app.
 *
 * <h3>Supported Endpoints</h3>
 * <ul>
 *   <li>{@code GET  /status}       → current timer status as JSON</li>
 *   <li>{@code POST /set?minutes=N} → start/reset timer for N minutes</li>
 *   <li>{@code POST /cancel}       → cancel the active timer</li>
 *   <li>{@code OPTIONS *}          → CORS preflight response</li>
 * </ul>
 *
 * <p>All responses include {@code Access-Control-Allow-Origin: *} for
 * cross-origin access from the companion web app.</p>
 */
public class MiniHttpServer {

    private static final String TAG = "MiniHttpServer";

    private final int port;
    private final SleepService service;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;

    /**
     * Creates a new HTTP server instance.
     *
     * @param port    TCP port to listen on
     * @param service reference to the owning {@link SleepService}
     */
    public MiniHttpServer(int port, SleepService service) {
        this.port = port;
        this.service = service;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Start / Stop
    // ──────────────────────────────────────────────────────────────────────────

    /** Starts the server's accept loop on a background thread. */
    public void start() {
        running = true;
        acceptThread = new Thread(this::acceptLoop, "MiniHttpServer-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        Log.i(TAG, "HTTP server starting on port " + port);
    }

    /** Shuts down the server. Closes the socket and interrupts the accept thread. */
    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            Log.w(TAG, "Error closing server socket", e);
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
        Log.i(TAG, "HTTP server stopped");
    }

    /** Checks if the server is actively listening on its port. */
    public boolean isListening() {
        return running && serverSocket != null && !serverSocket.isClosed() && serverSocket.isBound();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Accept loop
    // ──────────────────────────────────────────────────────────────────────────

    /** Main accept loop with automatic reconnect & boot retry resilience. */
    private void acceptLoop() {
        while (running) {
            try {
                if (serverSocket == null || serverSocket.isClosed()) {
                    serverSocket = new ServerSocket();
                    serverSocket.setReuseAddress(true);
                    serverSocket.bind(new java.net.InetSocketAddress(port));
                    Log.i(TAG, "MiniHttpServer successfully listening on port " + port);
                }

                while (running && serverSocket != null && !serverSocket.isClosed()) {
                    try {
                        Socket client = serverSocket.accept();
                        // Handle each connection on its own thread
                        new Thread(() -> handleConnection(client), "MiniHttp-Handler")
                                .start();
                    } catch (SocketException e) {
                        if (running) {
                            Log.w(TAG, "SocketException in accept (network change/sleep): " + e.getMessage());
                            break; // Break inner loop to recreate socket cleanly
                        }
                    }
                }
            } catch (IOException e) {
                if (running) {
                    Log.w(TAG, "Failed to bind or listen on port " + port + " (network starting?), retrying in 2s...", e);
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            } finally {
                if (serverSocket != null && running) {
                    try {
                        serverSocket.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        Log.i(TAG, "MiniHttpServer acceptLoop terminated");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Connection handler
    // ──────────────────────────────────────────────────────────────────────────

    /** Reads one HTTP request from the client, routes it, and writes a response. */
    private void handleConnection(Socket client) {
        try (client;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = client.getOutputStream()) {

            // Read the request line (e.g. "GET /status HTTP/1.1")
            String requestLine = in.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            Log.d(TAG, "Request: " + requestLine);

            // Consume remaining headers (we don't need them, but must read them)
            String header;
            while ((header = in.readLine()) != null && !header.isEmpty()) {
                // no-op
            }

            // Parse method, path, and query string
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                sendResponse(out, 400, "{\"error\":\"Bad request\"}");
                return;
            }
            String method = parts[0].toUpperCase();
            String fullPath = parts[1]; // e.g. "/set?minutes=30"

            String path;
            Map<String, String> queryParams = new HashMap<>();
            int qIdx = fullPath.indexOf('?');
            if (qIdx >= 0) {
                path = fullPath.substring(0, qIdx);
                parseQueryString(fullPath.substring(qIdx + 1), queryParams);
            } else {
                path = fullPath;
            }

            // Route the request
            routeRequest(method, path, queryParams, out);

        } catch (IOException e) {
            Log.w(TAG, "Connection error", e);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Routing
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Routes the parsed request to the appropriate handler method.
     */
    private void routeRequest(String method, String path,
                              Map<String, String> params, OutputStream out)
            throws IOException {

        // Handle CORS preflight for any path
        if ("OPTIONS".equals(method)) {
            sendCorsPreflightResponse(out);
            return;
        }

        switch (path) {
            case "/status":
                handleStatus(out);
                break;

            case "/set":
                handleSet(params, out);
                break;

            case "/cancel":
                handleCancel(out);
                break;

            default:
                sendResponse(out, 404, "{\"error\":\"Not found\"}");
                break;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Endpoint handlers
    // ──────────────────────────────────────────────────────────────────────────

    /** GET /status → returns current timer status as JSON. */
    private void handleStatus(OutputStream out) throws IOException {
        sendResponse(out, 200, service.getStatusJson());
    }

    /** POST /set?minutes=N → sets the timer for N minutes. */
    private void handleSet(Map<String, String> params, OutputStream out)
            throws IOException {
        String minutesStr = params.get("minutes");
        if (minutesStr == null || minutesStr.isEmpty()) {
            sendResponse(out, 400,
                    "{\"error\":\"Missing 'minutes' parameter\"}");
            return;
        }

        try {
            int minutes = Integer.parseInt(minutesStr);
            if (minutes <= 0 || minutes > 1440) { // max 24 hours
                sendResponse(out, 400,
                        "{\"error\":\"Minutes must be between 1 and 1440\"}");
                return;
            }
            service.setTimer(minutes);
            sendResponse(out, 200, service.getStatusJson());
        } catch (NumberFormatException e) {
            sendResponse(out, 400,
                    "{\"error\":\"Invalid 'minutes' value\"}");
        }
    }

    /** POST /cancel → cancels the active timer. */
    private void handleCancel(OutputStream out) throws IOException {
        service.cancelTimer();
        sendResponse(out, 200, service.getStatusJson());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // HTTP response helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Sends a complete HTTP response with JSON body and CORS headers.
     *
     * @param out        output stream to the client
     * @param statusCode HTTP status code (e.g. 200, 400, 404)
     * @param jsonBody   JSON string to send as the response body
     */
    private void sendResponse(OutputStream out, int statusCode, String jsonBody)
            throws IOException {
        String statusText = statusCode == 200 ? "OK"
                : statusCode == 400 ? "Bad Request"
                : statusCode == 404 ? "Not Found"
                : "Error";

        byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);

        String response = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                + "Access-Control-Allow-Headers: Content-Type\r\n"
                + "Connection: close\r\n"
                + "\r\n";

        out.write(response.getBytes(StandardCharsets.UTF_8));
        out.write(bodyBytes);
        out.flush();
    }

    /** Sends a 204 No Content response for CORS preflight (OPTIONS) requests. */
    private void sendCorsPreflightResponse(OutputStream out) throws IOException {
        String response = "HTTP/1.1 204 No Content\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                + "Access-Control-Allow-Headers: Content-Type\r\n"
                + "Access-Control-Max-Age: 86400\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        out.write(response.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Query string parser
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Parses a URL query string (e.g. "minutes=30&foo=bar") into a map.
     *
     * @param queryString raw query string (without the leading '?')
     * @param params      map to populate with key-value pairs
     */
    private void parseQueryString(String queryString, Map<String, String> params) {
        if (queryString == null || queryString.isEmpty()) return;
        for (String pair : queryString.split("&")) {
            int eq = pair.indexOf('=');
            try {
                if (eq > 0) {
                    String key = URLDecoder.decode(pair.substring(0, eq), "UTF-8");
                    String value = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                    params.put(key, value);
                } else {
                    params.put(URLDecoder.decode(pair, "UTF-8"), "");
                }
            } catch (Exception e) {
                Log.w(TAG, "Error decoding param: " + pair, e);
            }
        }
    }
}
