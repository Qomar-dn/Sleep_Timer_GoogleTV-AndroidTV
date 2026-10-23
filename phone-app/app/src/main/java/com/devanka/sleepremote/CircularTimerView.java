package com.devanka.sleepremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Matrix;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A custom circular timer view that draws an arc-based progress indicator
 * with a neon purple-to-cyan gradient. Displays a sleep emoji icon in the center.
 */
public class CircularTimerView extends View {

    // Arc colors
    private static final int COLOR_PURPLE = 0xFF7B2FBE;
    private static final int COLOR_CYAN = 0xFF00D4FF;
    private static final int COLOR_BG_CIRCLE = 0xFF2A2A3E;
    private static final int COLOR_INNER_BG = 0xFF0D0D1A;

    // Paints
    private final Paint backgroundCirclePaint;
    private final Paint arcPaint;
    private final Paint innerBgPaint;
    private final Paint emojiPaint;

    // Drawing geometry
    private final RectF arcRect = new RectF();
    private float strokeWidth;

    // Timer state
    private int maxSeconds = 0;
    private int remainingSeconds = 0;

    public CircularTimerView(Context context) {
        this(context, null);
    }

    public CircularTimerView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CircularTimerView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        strokeWidth = dpToPx(12);

        // Background circle paint (the track)
        backgroundCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        backgroundCirclePaint.setStyle(Paint.Style.STROKE);
        backgroundCirclePaint.setStrokeWidth(strokeWidth);
        backgroundCirclePaint.setColor(COLOR_BG_CIRCLE);
        backgroundCirclePaint.setStrokeCap(Paint.Cap.ROUND);

        // Arc paint (the progress)
        arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(strokeWidth);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        // Inner background paint
        innerBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        innerBgPaint.setStyle(Paint.Style.FILL);
        innerBgPaint.setColor(COLOR_INNER_BG);

        // Emoji paint for the center icon
        emojiPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        emojiPaint.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float padding = strokeWidth / 2f + dpToPx(4);
        arcRect.set(padding, padding, w - padding, h - padding);
        updateShader();
    }

    /**
     * Creates the sweep gradient shader for the arc, rotating it so
     * the gradient sweeps from purple (start) to cyan (end).
     */
    private void updateShader() {
        float cx = arcRect.centerX();
        float cy = arcRect.centerY();

        SweepGradient gradient = new SweepGradient(cx, cy,
                new int[]{COLOR_PURPLE, COLOR_CYAN, COLOR_PURPLE},
                new float[]{0f, 0.5f, 1f});

        // Rotate the gradient so it starts from the top (-90 degrees)
        Matrix gradientMatrix = new Matrix();
        gradientMatrix.preRotate(-90, cx, cy);
        gradient.setLocalMatrix(gradientMatrix);

        arcPaint.setShader(gradient);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        float cx = arcRect.centerX();
        float cy = arcRect.centerY();
        float radius = arcRect.width() / 2f;

        // Draw inner dark background
        canvas.drawCircle(cx, cy, radius - strokeWidth / 2f, innerBgPaint);

        // Draw background track circle
        canvas.drawArc(arcRect, 0, 360, false, backgroundCirclePaint);

        // Draw progress arc
        float progress = (maxSeconds > 0) ? (float) remainingSeconds / maxSeconds : 0f;
        if (progress > 0f) {
            float sweepAngle = 360f * progress;
            // Draw from the top (-90 degrees), sweeping clockwise
            canvas.drawArc(arcRect, -90, sweepAngle, false, arcPaint);
        }

        // Draw center emoji
        float emojiSize = radius * 0.45f;
        emojiPaint.setTextSize(emojiSize);
        float emojiY = cy + emojiSize * 0.3f;
        canvas.drawText("💤", cx, emojiY, emojiPaint);
    }

    /**
     * Sets the maximum total seconds for the timer (used to calculate progress).
     *
     * @param maxSeconds The total duration of the timer in seconds.
     */
    public void setMaxSeconds(int maxSeconds) {
        this.maxSeconds = maxSeconds;
        invalidate();
    }

    /**
     * Sets the remaining seconds on the timer (used to draw the arc).
     *
     * @param remainingSeconds The seconds remaining.
     */
    public void setRemainingSeconds(int remainingSeconds) {
        this.remainingSeconds = remainingSeconds;
        invalidate();
    }

    /**
     * Converts dp to pixels using the display density.
     */
    private float dpToPx(float dp) {
        return dp * getContext().getResources().getDisplayMetrics().density;
    }
}
