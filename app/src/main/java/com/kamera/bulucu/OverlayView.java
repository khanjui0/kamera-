package com.kamera.bulucu;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Draws the green boxes on top of the camera preview, gently pulsing so they catch the eye. */
public class OverlayView extends View {

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private List<float[]> boxes = new ArrayList<>();

    public OverlayView(Context context) {
        super(context);
        float d = getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setColor(0xFF00E676);
        stroke.setShadowLayer(3f * d, 0f, 0f, 0xAA000000);
        fill.setStyle(Paint.Style.FILL);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    /** Each box is {left, top, right, bottom} normalised to 0..1. */
    public void setBoxes(List<float[]> boxes) {
        this.boxes = boxes;
        invalidate();
    }

    public List<float[]> boxes() {
        return boxes;
    }

    /** The box drawn at a touch point (with the same padding it is drawn with), or null. */
    public float[] boxAt(float x, float y) {
        float[] hit = null;
        float best = Float.MAX_VALUE;
        for (float[] b : boxes) {
            screenRect(b, rect);
            float slack = 12f * getResources().getDisplayMetrics().density;
            if (x < rect.left - slack || x > rect.right + slack || y < rect.top - slack || y > rect.bottom + slack) continue;
            float dx = x - rect.centerX(), dy = y - rect.centerY(), d = dx * dx + dy * dy;
            if (d < best) {
                best = d;
                hit = b;
            }
        }
        return hit;
    }

    /** Where a box is drawn: padded, and never smaller than a fingertip. */
    void screenRect(float[] b, RectF out) {
        float d = getResources().getDisplayMetrics().density;
        float pad = 8f * d, minSide = 36f * d;
        int vw = getWidth(), vh = getHeight();
        float cx = (b[0] + b[2]) / 2f * vw, cy = (b[1] + b[3]) / 2f * vh;
        float hw = Math.max((b[2] - b[0]) * vw / 2f + pad, minSide / 2f);
        float hh = Math.max((b[3] - b[1]) * vh / 2f + pad, minSide / 2f);
        out.set(cx - hw, cy - hh, cx + hw, cy + hh);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (boxes.isEmpty()) return;
        float d = getResources().getDisplayMetrics().density;
        float corner = 8f * d;
        double phase = (SystemClock.uptimeMillis() % 1000L) / 1000.0 * 2 * Math.PI;
        float pulse = (float) (0.5 + 0.5 * Math.sin(phase));
        stroke.setStrokeWidth((2.5f + 2f * pulse) * d);
        fill.setColor(((int) (0x22 + 0x22 * pulse) << 24) | 0x00E676);
        for (float[] b : boxes) {
            screenRect(b, rect);
            canvas.drawRoundRect(rect, corner, corner, fill);
            canvas.drawRoundRect(rect, corner, corner, stroke);
        }
        postInvalidateOnAnimation();
    }
}
