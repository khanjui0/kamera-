package com.kamera.bulucu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Finds small things that look different from the floor around them.
 *
 * Works on a low-resolution Y/U/V grid. Each cell is compared with the average of a large
 * neighbourhood (the "floor"), the difference is scaled by how much the floor itself normally
 * varies, and connected regions of cells that stand out are reported as boxes. Big regions are
 * dropped because they are carpet patterns, shadows or furniture rather than a lost object.
 * Plain Java so it can be tested off the device.
 */
public final class FloorDetector {

    public static final class Box {
        /** Normalised to 0..1 in grid coordinates. */
        public final float left, top, right, bottom;
        public final float score;

        Box(float left, float top, float right, float bottom, float score) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.score = score;
        }
    }

    public static final int MAX_BOXES = 6;

    private final int w, h, n;
    private final int bgRadius;

    private final float[][] fineCh, bgCh;
    private final float[] z, weight, tmp;
    private final double[] sumV, sumW;
    private final int[] hist = new int[1024];
    private final int[] label, queue;

    /** Higher means more things are reported. 0..100. */
    private volatile int sensitivity = 50;
    /** True when the last frame had so much going on that nothing trustworthy could be picked out. */
    private boolean busy;

    public FloorDetector(int w, int h) {
        this.w = w;
        this.h = h;
        this.n = w * h;
        this.bgRadius = Math.max(4, Math.min(w, h) / 9);
        fineCh = new float[3][n];
        bgCh = new float[3][n];
        z = new float[n];
        weight = new float[n];
        tmp = new float[n];
        sumV = new double[(w + 1) * (h + 1)];
        sumW = new double[(w + 1) * (h + 1)];
        label = new int[n];
        queue = new int[n];
    }

    public int width() {
        return w;
    }

    public int height() {
        return h;
    }

    public void setSensitivity(int value) {
        sensitivity = Math.max(0, Math.min(100, value));
    }

    public boolean wasBusy() {
        return busy;
    }

    /** Threshold on the combined, noise-normalised difference. */
    float threshold() {
        return 7.5f - 0.04f * sensitivity;
    }

    /** y, u and v are w*h arrays; u and v centred on 0. */
    public List<Box> detect(float[] y, float[] u, float[] v) {
        float[][] ch = {y, u, v};
        for (int c = 0; c < 3; c++) boxBlur(ch[c], null, 1, fineCh[c]);

        // First pass: plain neighbourhood average.
        for (int c = 0; c < 3; c++) boxBlur(fineCh[c], null, bgRadius, bgCh[c]);
        float t = threshold();
        score();

        // Second pass: leave out what already stands out, so an object doesn't tint its own floor.
        for (int i = 0; i < n; i++) weight[i] = z[i] > t * 0.5f ? 0f : 1f;
        for (int c = 0; c < 3; c++) boxBlur(fineCh[c], weight, bgRadius, bgCh[c]);
        score();

        return components(t);
    }

    private static final float[] MIN_SCALE = {1.5f, 1.0f, 1.0f};

    /** Fills z with the per-cell difference from the floor, in units of typical floor variation. */
    private void score() {
        float[] scale = new float[3];
        for (int c = 0; c < 3; c++) {
            float[] f = fineCh[c], b = bgCh[c];
            for (int i = 0; i < n; i++) tmp[i] = Math.abs(f[i] - b[i]);
            scale[c] = Math.max(MIN_SCALE[c], 1.4826f * median(tmp));
        }
        for (int i = 0; i < n; i++) {
            float sum = 0f;
            for (int c = 0; c < 3; c++) {
                float d = (fineCh[c][i] - bgCh[c][i]) / scale[c];
                sum += d * d;
            }
            z[i] = (float) Math.sqrt(sum);
        }
    }

    /** Median of non-negative values, to 1/16 precision, without sorting. */
    private float median(float[] a) {
        java.util.Arrays.fill(hist, 0);
        int top = hist.length - 1;
        for (int i = 0; i < n; i++) {
            int b = (int) (a[i] * 16f);
            hist[b > top ? top : b]++;
        }
        int half = n / 2, acc = 0;
        for (int b = 0; b <= top; b++) {
            acc += hist[b];
            if (acc > half) return (b + 0.5f) / 16f;
        }
        return top / 16f;
    }

    /** Mean over a (2r+1)^2 window, optionally weighted; windows are clipped at the edges. */
    private void boxBlur(float[] src, float[] wts, int r, float[] dst) {
        int stride = w + 1;
        for (int x = 0; x <= w; x++) {
            sumV[x] = 0;
            sumW[x] = 0;
        }
        for (int yy = 0; yy < h; yy++) {
            double rowV = 0, rowW = 0;
            int row = (yy + 1) * stride, prev = yy * stride;
            sumV[row] = 0;
            sumW[row] = 0;
            for (int x = 0; x < w; x++) {
                int i = yy * w + x;
                float wt = wts == null ? 1f : wts[i];
                rowV += src[i] * wt;
                rowW += wt;
                sumV[row + x + 1] = sumV[prev + x + 1] + rowV;
                sumW[row + x + 1] = sumW[prev + x + 1] + rowW;
            }
        }
        for (int yy = 0; yy < h; yy++) {
            int y0 = Math.max(0, yy - r), y1 = Math.min(h, yy + r + 1);
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - r), x1 = Math.min(w, x + r + 1);
                int a = y0 * stride + x0, b = y0 * stride + x1, c = y1 * stride + x0, d = y1 * stride + x1;
                double sw = sumW[d] - sumW[b] - sumW[c] + sumW[a];
                int i = yy * w + x;
                if (sw < 1.0) {
                    // Almost everything around was left out; fall back to the plain average.
                    double cnt = (double) (y1 - y0) * (x1 - x0);
                    dst[i] = wts == null ? src[i] : unweighted(src, x0, y0, x1, y1, cnt);
                } else {
                    dst[i] = (float) ((sumV[d] - sumV[b] - sumV[c] + sumV[a]) / sw);
                }
            }
        }
    }

    private float unweighted(float[] src, int x0, int y0, int x1, int y1, double cnt) {
        double s = 0;
        for (int yy = y0; yy < y1; yy++) {
            for (int x = x0; x < x1; x++) s += src[yy * w + x];
        }
        return (float) (s / cnt);
    }

    /** Groups standing-out cells (hysteresis + 8-connectivity) and keeps the small, solid ones. */
    private List<Box> components(float t) {
        float low = t * 0.6f;
        java.util.Arrays.fill(label, 0);
        int strong = 0;
        for (int i = 0; i < n; i++) if (z[i] > t) strong++;
        busy = strong > n * 0.12f;
        List<Box> out = new ArrayList<>();
        if (busy) return out;

        int maxW = Math.round(w * 0.35f), maxH = Math.round(h * 0.35f);
        int maxArea = Math.round(n * 0.05f);
        int next = 0;
        for (int seed = 0; seed < n; seed++) {
            if (label[seed] != 0 || z[seed] <= t) continue;
            next++;
            int head = 0, tail = 0;
            queue[tail++] = seed;
            label[seed] = next;
            int minX = w, minY = h, maxX = -1, maxY = -1, area = 0;
            float peak = 0f, total = 0f;
            while (head < tail) {
                int i = queue[head++];
                int x = i % w, yy = i / w;
                area++;
                total += z[i];
                if (z[i] > peak) peak = z[i];
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (yy < minY) minY = yy;
                if (yy > maxY) maxY = yy;
                for (int dy = -1; dy <= 1; dy++) {
                    int ny = yy + dy;
                    if (ny < 0 || ny >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx;
                        if (nx < 0 || nx >= w) continue;
                        int j = ny * w + nx;
                        if (label[j] == 0 && z[j] > low) {
                            label[j] = next;
                            queue[tail++] = j;
                        }
                    }
                }
            }
            int bw = maxX - minX + 1, bh = maxY - minY + 1;
            if (area < 3 || area > maxArea || bw > maxW || bh > maxH) continue;
            // Long thin streaks are pattern edges, not objects.
            if (area < 0.2f * bw * bh) continue;
            float score = peak * (float) Math.sqrt(area) + total / area;
            out.add(new Box(minX / (float) w, minY / (float) h,
                    (maxX + 1) / (float) w, (maxY + 1) / (float) h, score));
        }
        Collections.sort(out, (a, b) -> Float.compare(b.score, a.score));
        return out.size() > MAX_BOXES ? new ArrayList<>(out.subList(0, MAX_BOXES)) : out;
    }
}
