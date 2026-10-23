package com.devanka.sleepserver;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * FloatingTimerOverlay — Displays a floating gaming-themed HUD overlay
 * in the top-right corner of the TV screen showing remaining rental time.
 *
 * <p>Uses {@link WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY} with
 * {@code FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE} so it never intercepts
 * remote control navigation or PS4 game controller inputs.</p>
 */
public class FloatingTimerOverlay {

    private static final String TAG = "FloatingTimerOverlay";

    private final Context context;
    private final WindowManager windowManager;

    private View overlayView;
    private LinearLayout floatingRoot;
    private TextView floatingTitle;
    private TextView floatingTime;
    private TextView floatingIcon;

    private boolean isVisible = false;

    public FloatingTimerOverlay(Context context) {
        this.context = context;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    /**
     * Shows the floating HUD overlay on screen.
     */
    public void show() {
        if (isVisible) return;

        // Check if overlay permission is granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Cannot show overlay: SYSTEM_ALERT_WINDOW permission not granted");
            return;
        }

        try {
            if (overlayView == null) {
                LayoutInflater inflater = LayoutInflater.from(context);
                overlayView = inflater.inflate(R.layout.layout_floating_timer, null);
                floatingRoot = overlayView.findViewById(R.id.floatingRoot);
                floatingTitle = overlayView.findViewById(R.id.floatingTitle);
                floatingTime = overlayView.findViewById(R.id.floatingTime);
                floatingIcon = overlayView.findViewById(R.id.floatingIcon);
            }

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
            );

            params.gravity = Gravity.TOP | Gravity.END;
            // Hug the top-right corner closely
            params.x = 10;
            params.y = 8;
            params.alpha = 0.80f;

            windowManager.addView(overlayView, params);
            isVisible = true;
            Log.i(TAG, "Floating timer HUD added to screen");
        } catch (Exception e) {
            Log.e(TAG, "Failed to add floating overlay", e);
        }
    }

    /**
     * Updates the remaining time display on the HUD.
     *
     * @param remainingSeconds seconds remaining on the rental timer
     */
    public void update(int remainingSeconds) {
        if (!isVisible || overlayView == null) return;

        int hours = remainingSeconds / 3600;
        int minutes = (remainingSeconds % 3600) / 60;
        int seconds = remainingSeconds % 60;

        String formatted = String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds);
        floatingTime.setText(formatted);

        // Warning state when under 5 minutes (300 seconds)
        if (remainingSeconds <= 300 && remainingSeconds > 0) {
            floatingRoot.setBackgroundResource(R.drawable.bg_floating_timer_warning);
            floatingTitle.setText("⚠️ HABIS ");
            floatingTitle.setTextColor(0xFFFF3B5C);
            floatingTime.setTextColor(0xFFFF3B5C);
            floatingIcon.setText("⏳");
        } else {
            floatingRoot.setBackgroundResource(R.drawable.bg_floating_timer);
            floatingTitle.setText("SEWA ");
            floatingTitle.setTextColor(0xFF00D4FF);
            floatingTime.setTextColor(0xFFFFFFFF);
            floatingIcon.setText("🎮");
        }
    }

    /**
     * Hides and removes the floating HUD overlay from screen.
     */
    public void hide() {
        if (!isVisible || overlayView == null) return;

        try {
            windowManager.removeView(overlayView);
            isVisible = false;
            Log.i(TAG, "Floating timer HUD removed from screen");
        } catch (Exception e) {
            Log.w(TAG, "Error removing overlay view", e);
        }
    }

    /**
     * Cleans up all resources.
     */
    public void destroy() {
        hide();
        overlayView = null;
    }
}
