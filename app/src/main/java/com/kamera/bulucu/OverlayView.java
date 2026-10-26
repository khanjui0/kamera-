package com.kamera.bulucu;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Draws the green boxes on top of the camera preview. */
public class OverlayView extends View {

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private List<float[]> boxes = new ArrayList<>();

    public OverlayView(Context context) {
        super(context);
        float d = getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(3f * d);
        stroke.setColor(0xFF00E676);
        stroke.setShadowLayer(3f * d, 0f, 0f, 0xAA000000);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0x3300E676);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    /** Each box is {left, top, right, bottom} normalised to 0..1. */
    public void setBoxes(List<float[]> boxes) {
        this.boxes = boxes;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float d = getResources().getDisplayMetrics().density;
        float pad = 8f * d, minSide = 36f * d, corner = 8f * d;
        int vw = getWidth(), vh = getHeight();
        for (float[] b : boxes) {
            float cx = (b[0] + b[2]) / 2f * vw, cy = (b[1] + b[3]) / 2f * vh;
            float hw = Math.max((b[2] - b[0]) * vw / 2f + pad, minSide / 2f);
            float hh = Math.max((b[3] - b[1]) * vh / 2f + pad, minSide / 2f);
            rect.set(cx - hw, cy - hh, cx + hw, cy + hh);
            canvas.drawRoundRect(rect, corner, corner, fill);
            canvas.drawRoundRect(rect, corner, corner, stroke);
        }
    }
}
