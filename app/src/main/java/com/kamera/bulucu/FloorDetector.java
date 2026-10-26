package com.kamera.bulucu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Finds a small thing lying on a surface - any surface: a carpet, concrete, a table top, a car
 * seat, tiles, grass - by looking for what doesn't belong to it.
 *
 * Works on a low-resolution Y/U/V grid:
 * <ol>
 * <li>Uneven lighting is taken out by subtracting the surface's local level (a median over a large
 * neighbourhood, so feet and furniture in the picture don't shift it).</li>
 * <li>The surface's own colours are learned (one for concrete, two for stripes, several for a
 * mosaic). Each cell gets an "energy": the mean, over its 3x3 neighbourhood, of the squared
 * distance to the nearest of those colours, so a bright rim and its dark shadow add up instead of
 * cancelling. Divided by the frame's median energy, the surface's own texture lands near 1
 * whatever the surface is.</li>
 * <li>Connected regions that stand out become candidates. A lost thing is a small island: wholly in
 * view and surrounded by surface. So regions that are too big, touch the edge of the picture, join
 * up with something big (a foot, a chair leg), or are just part of a line (a seam, a crack, grout)
 * are dropped.</li>
 * <li>Odd one out: a candidate that looks like several others in the same frame is part of the
 * pattern (polka dots, pebbles, chips in terrazzo), not something dropped on it.</li>
 * </ol>
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

    /** Energy threshold at sensitivity 0, and how much it drops per sensitivity step. */
    private static final float THRESHOLD_AT_0 = 3.2f, THRESHOLD_SLOPE = 0.012f;
    /** Energy that is clearly above the surface's own texture; used to test that a find is an island. */
    private static final float FAINT = 1.5f;
    /** Below these, differences are sensor noise (grey levels per channel). */
    private static final float[] MIN_RAW_SCALE = {2f, 1.2f, 1.2f};
    /** Largest find, as a share of the picture's width/height and area. */
    private static final float MAX_SIDE = 0.25f, MAX_AREA = 0.03f;
    /** A candidate with this many look-alikes in the frame is part of the pattern. */
    private static final int LOOK_ALIKES = 3;
    /** More distinct candidates than this and the surface is too busy to trust any one. */
    private static final int BUSY_CANDIDATES = 10;

    private final int w, h, n;
    private final int bgRadius;

    private final float[][] fineCh, bgCh, resid;
    private final float[] z, weight, tmp, sq;
    private final double[] sumV, sumW;
    private final int[] hist = new int[1024];
    private final int[] label, queue, ext;
    private int extStamp;

    /** Higher means more things are reported. 0..100. */
    private volatile int sensitivity = 50;
    /** True when the last frame had so much going on that nothing trustworthy could be picked out. */
    private boolean busy;

    public FloorDetector(int w, int h) {
        this.w = w;
        this.h = h;
        this.n = w * h;
        this.bgRadius = Math.max(4, Math.min(w, h) / 9);
        this.colourRadius = Math.max(8, Math.min(w, h) / 6);
        fineCh = new float[3][n];
        bgCh = new float[3][n];
        resid = new float[3][n];
        z = new float[n];
        weight = new float[n];
        tmp = new float[n];
        sq = new float[n];
        sumV = new double[(w + 1) * (h + 1)];
        sumW = new double[(w + 1) * (h + 1)];
        label = new int[n];
        queue = new int[n];
        ext = new int[n];
        colourOf = new int[n];
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

    float threshold() {
        return THRESHOLD_AT_0 - THRESHOLD_SLOPE * sensitivity;
    }

    /** y, u and v are w*h arrays; u and v centred on 0. */
    public List<Box> detect(float[] y, float[] u, float[] v) {
        float[][] ch = {y, u, v};
        for (int c = 0; c < 3; c++) boxBlur(ch[c], null, 1, fineCh[c]);

        for (int c = 0; c < 3; c++) surfaceLevel(fineCh[c], bgCh[c]);

        energyScore(ch);
        return components(threshold());
    }

    private static final int BG_BLOCK = 8;
    /** Samples per side of each median window; enough for a stable median at any grid size. */
    private static final int BG_SAMPLES = 13;
    private float[] blockMed, medBuf;

    /**
     * The surface's own level at every cell, with lighting falloff: medians over windows about a
     * fifth of the picture across, on a coarse grid, blended between. A median ignores anything
     * covering less than half the window - a foot, trousers, a chair leg - where an average would
     * be dragged towards it and make the floor beside it look unusual.
     */
    private void surfaceLevel(float[] src, float[] dst) {
        int bx = (w + BG_BLOCK - 1) / BG_BLOCK, by = (h + BG_BLOCK - 1) / BG_BLOCK;
        int step = Math.max(1, (2 * bgRadius + 1) / BG_SAMPLES);
        if (blockMed == null) {
            blockMed = new float[bx * by];
            int perSide = (2 * bgRadius) / step + 1;
            medBuf = new float[perSide * perSide];
        }
        for (int j = 0; j < by; j++) {
            for (int k = 0; k < bx; k++) {
                int cx = Math.min(w - 1, k * BG_BLOCK + BG_BLOCK / 2), cy = Math.min(h - 1, j * BG_BLOCK + BG_BLOCK / 2);
                int x0 = Math.max(0, cx - bgRadius), x1 = Math.min(w - 1, cx + bgRadius);
                int y0 = Math.max(0, cy - bgRadius), y1 = Math.min(h - 1, cy + bgRadius);
                int m = 0;
                for (int y = y0; y <= y1; y += step) {
                    for (int x = x0; x <= x1; x += step) medBuf[m++] = src[y * w + x];
                }
                blockMed[j * bx + k] = select(medBuf, m, m / 2);
            }
        }
        for (int y = 0; y < h; y++) {
            float fy = Math.max(0f, Math.min(by - 1f, (y - BG_BLOCK / 2f) / BG_BLOCK));
            int j0 = (int) fy, j1 = Math.min(by - 1, j0 + 1);
            float ty = fy - j0;
            for (int x = 0; x < w; x++) {
                float fx = Math.max(0f, Math.min(bx - 1f, (x - BG_BLOCK / 2f) / BG_BLOCK));
                int k0 = (int) fx, k1 = Math.min(bx - 1, k0 + 1);
                float tx = fx - k0;
                float top = blockMed[j0 * bx + k0] * (1 - tx) + blockMed[j0 * bx + k1] * tx;
                float bot = blockMed[j1 * bx + k0] * (1 - tx) + blockMed[j1 * bx + k1] * tx;
                dst[y * w + x] = top * (1 - ty) + bot * ty;
            }
        }
    }

    /**
     * Fills z with each cell's 3x3 energy relative to the frame's median, and resid with the
     * scaled differences from the surface average. Energy is measured from the nearest of the
     * surface's own colours, so a striped or black-and-white floor doesn't make every cell look
     * unusual and drown out the one thing that really is.
     */
    private void energyScore(float[][] ch) {
        float[] scale = new float[3];
        for (int c = 0; c < 3; c++) {
            float[] f = ch[c], b = bgCh[c];
            for (int i = 0; i < n; i++) tmp[i] = Math.abs(f[i] - b[i]);
            scale[c] = Math.max(MIN_RAW_SCALE[c], 1.4826f * median(tmp));
        }
        for (int i = 0; i < n; i++) {
            for (int c = 0; c < 3; c++) resid[c][i] = (ch[c][i] - bgCh[c][i]) / scale[c];
        }
        learnPalette();
        // Which of those colours each cell is, and how much they vary within themselves - the
        // yardstick for "different", rather than how different the colours are from each other.
        for (int i = 0; i < n; i++) {
            float r0 = resid[0][i], r1 = resid[1][i], r2 = resid[2][i];
            float best = Float.MAX_VALUE;
            int bestK = 0;
            for (int k = 0; k < paletteSize; k++) {
                float a = r0 - palette[k][0], b = r1 - palette[k][1], c = r2 - palette[k][2];
                float d = a * a + b * b + c * c;
                if (d < best) {
                    best = d;
                    bestK = k;
                }
            }
            colourOf[i] = bestK;
        }
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < n; i++) tmp[i] = Math.abs(resid[c][i] - palette[colourOf[i]][c]);
            within[c] = Math.max(MIN_RAW_SCALE[c] / scale[c], 1.4826f * median(tmp));
        }
        // Distance to the nearest of the colours actually found around the cell: the colour of
        // a shoe at the edge of the picture doesn't make a white cap in the middle look normal.
        Arrays.fill(sq, Float.MAX_VALUE);
        for (int k = 0; k < paletteSize; k++) {
            for (int i = 0; i < n; i++) weight[i] = colourOf[i] == k ? 1f : 0f;
            boxBlur(weight, null, colourRadius, tmp);
            float p0 = palette[k][0], p1 = palette[k][1], p2 = palette[k][2];
            for (int i = 0; i < n; i++) {
                if (tmp[i] < COLOUR_SHARE) continue;
                float a = (resid[0][i] - p0) / within[0], b = (resid[1][i] - p1) / within[1], c = (resid[2][i] - p2) / within[2];
                float d = a * a + b * b + c * c;
                if (d < sq[i]) sq[i] = d;
            }
        }
        for (int i = 0; i < n; i++) {
            // No common colour around at all (inside something big): use the cell's own nearest.
            if (sq[i] != Float.MAX_VALUE) continue;
            float[] pc = palette[colourOf[i]];
            float a = (resid[0][i] - pc[0]) / within[0], b = (resid[1][i] - pc[1]) / within[1], c = (resid[2][i] - pc[2]) / within[2];
            sq[i] = a * a + b * b + c * c;
        }
        boxBlur(sq, null, 1, tmp);
        for (int i = 0; i < n; i++) tmp[i] = (float) Math.sqrt(tmp[i]);
        float m = Math.max(0.5f, median(tmp));
        for (int i = 0; i < n; i++) z[i] = tmp[i] / m;
    }

    /** The k-th smallest of a[0..n), reordering a (Hoare's quickselect). */
    private static float select(float[] a, int n, int k) {
        int lo = 0, hi = n - 1;
        while (lo < hi) {
            float pivot = a[(lo + hi) >>> 1];
            int i = lo, j = hi;
            while (i <= j) {
                while (a[i] < pivot) i++;
                while (a[j] > pivot) j--;
                if (i <= j) {
                    float t = a[i];
                    a[i] = a[j];
                    a[j] = t;
                    i++;
                    j--;
                }
            }
            if (k <= j) hi = j;
            else if (k >= i) lo = i;
            else break;
        }
        return a[k];
    }

    private static final int PALETTE_MAX = 8, PALETTE_STRIDE = 2, PALETTE_ITER = 6;
    /** A colour must cover this share of the picture to count as the surface's own. */
    private static final float PALETTE_SUPPORT = 0.025f;
    private final float[][] palette = new float[PALETTE_MAX][3];
    private final float[][] palSum = new float[PALETTE_MAX][3];
    private final int[] palCount = new int[PALETTE_MAX];
    private int paletteSize;
    private final int[] colourOf;
    /** How much each channel varies within one of the surface's colours (in resid units). */
    private final float[] within = new float[3];
    /** A colour counts as found around a cell if it covers this share of the window around it. */
    private static final float COLOUR_SHARE = 0.03f;
    private final int colourRadius;

    /**
     * The surface's main colours (as differences from its average): k-means over a sample of
     * cells, keeping only colours common enough that they can't be a dropped thing.
     */
    private void learnPalette() {
        // Spread the starting colours over the range of brightness, where patterns differ most.
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (int i = 0; i < n; i += PALETTE_STRIDE) {
            lo = Math.min(lo, resid[0][i]);
            hi = Math.max(hi, resid[0][i]);
        }
        int k = PALETTE_MAX;
        for (int j = 0; j < k; j++) {
            palette[j][0] = lo + (hi - lo) * (j + 0.5f) / k;
            palette[j][1] = (j % 3 - 1) * 1.5f;
            palette[j][2] = ((j / 3) % 3 - 1) * 1.5f;
        }
        int samples = 0;
        for (int it = 0; it < PALETTE_ITER; it++) {
            for (int j = 0; j < k; j++) {
                palCount[j] = 0;
                palSum[j][0] = palSum[j][1] = palSum[j][2] = 0f;
            }
            samples = 0;
            for (int y = 0; y < h; y += PALETTE_STRIDE) {
                for (int x = (y / PALETTE_STRIDE) % 2; x < w; x += PALETTE_STRIDE) {
                    int i = y * w + x;
                    float r0 = resid[0][i], r1 = resid[1][i], r2 = resid[2][i];
                    int bestJ = 0;
                    float best = Float.MAX_VALUE;
                    for (int j = 0; j < k; j++) {
                        float a = r0 - palette[j][0], b = r1 - palette[j][1], c = r2 - palette[j][2];
                        float d = a * a + b * b + c * c;
                        if (d < best) {
                            best = d;
                            bestJ = j;
                        }
                    }
                    palCount[bestJ]++;
                    palSum[bestJ][0] += r0;
                    palSum[bestJ][1] += r1;
                    palSum[bestJ][2] += r2;
                    samples++;
                }
            }
            for (int j = 0; j < k; j++) {
                if (palCount[j] == 0) continue;
                for (int c = 0; c < 3; c++) palette[j][c] = palSum[j][c] / palCount[j];
            }
        }
        int minCount = Math.max(1, Math.round(samples * PALETTE_SUPPORT));
        paletteSize = 0;
        for (int j = 0; j < k; j++) {
            if (palCount[j] < minCount) continue;
            System.arraycopy(palette[j], 0, palette[paletteSize], 0, 3);
            paletteSize++;
        }
        if (paletteSize == 0) {
            palette[0][0] = palette[0][1] = palette[0][2] = 0f;
            paletteSize = 1;
        }
    }

    /** Median of non-negative values, to 1/16 precision, without sorting. */
    private float median(float[] a) {
        Arrays.fill(hist, 0);
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

    /** A spot, with what it looks like for the odd-one-out test; box is set if it is a find. */
    private static final class Candidate {
        Box box;
        float dy, du, dv, area;
    }

    /** Groups standing-out cells (hysteresis + 8-connectivity) and keeps the small islands. */
    private List<Box> components(float t) {
        float low = Math.max(FAINT, t * 0.6f);
        Arrays.fill(label, 0);
        int maxW = Math.round(w * MAX_SIDE), maxH = Math.round(h * MAX_SIDE);
        int maxArea = Math.round(n * MAX_AREA);
        List<Candidate> found = new ArrayList<>();
        int next = 0;
        // Weaker spots are collected too, only to count as look-alikes: a speckled floor has many
        // faint specks like the one dark speck that happens to pass the threshold.
        for (int seed = 0; seed < n; seed++) {
            if (label[seed] != 0 || z[seed] <= low) continue;
            next++;
            int head = 0, tail = 0;
            queue[tail++] = seed;
            label[seed] = next;
            int minX = w, minY = h, maxX = -1, maxY = -1, area = 0;
            float peak = 0f, total = 0f, sy = 0f, su = 0f, sv = 0f;
            int peakAt = seed;
            while (head < tail) {
                int i = queue[head++];
                int x = i % w, yy = i / w;
                area++;
                total += z[i];
                sy += resid[0][i];
                su += resid[1][i];
                sv += resid[2][i];
                if (z[i] > peak) {
                    peak = z[i];
                    peakAt = i;
                }
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
            Candidate c = new Candidate();
            c.dy = sy / area / within[0];
            c.du = su / area / within[1];
            c.dv = sv / area / within[2];
            c.area = area;
            found.add(c);
            if (peak <= t) continue;
            // Feet, furniture legs and anything else coming in from the edge of the picture.
            if (minX == 0 || minY == 0 || maxX == w - 1 || maxY == h - 1) continue;
            // Long thin streaks are pattern edges, not objects.
            if (area < 0.2f * bw * bh) continue;
            if (!isolated(next, minX, minY, maxX, maxY, maxW, maxH, maxArea)) continue;
            if (onLine(peakAt % w, peakAt / w, minX, minY, maxX, maxY, total / area)) continue;
            c.box = new Box(minX / (float) w, minY / (float) h, (maxX + 1) / (float) w, (maxY + 1) / (float) h,
                    peak * (float) Math.sqrt(area) + total / area);
        }

        List<Box> out = new ArrayList<>();
        for (Candidate c : found) {
            if (c.box == null) continue;
            int alike = 0;
            for (Candidate o : found) if (o != c && alike(c, o)) alike++;
            if (alike < LOOK_ALIKES) out.add(c.box);
        }
        busy = out.size() > BUSY_CANDIDATES;
        if (busy) return new ArrayList<>();
        Collections.sort(out, (a, b) -> Float.compare(b.score, a.score));
        return out.size() > MAX_BOXES ? new ArrayList<>(out.subList(0, MAX_BOXES)) : out;
    }

    /** Same colour and brightness against the surface, and about the same size. */
    private static boolean alike(Candidate a, Candidate b) {
        float ratio = a.area > b.area ? a.area / b.area : b.area / a.area;
        if (ratio > 3f) return false;
        float ey = a.dy - b.dy, eu = a.du - b.du, ev = a.dv - b.dv;
        float dist = (float) Math.sqrt(ey * ey + eu * eu + ev * ev);
        float na = (float) Math.sqrt(a.dy * a.dy + a.du * a.du + a.dv * a.dv);
        float nb = (float) Math.sqrt(b.dy * b.dy + b.du * b.du + b.dv * b.dv);
        float lo = Math.min(na, nb), hi = Math.max(na, nb);
        if (hi > 2f * lo + 1f) return false;
        return dist < 0.4f * lo + 0.8f;
    }

    /**
     * Grows the region through anything clearly unlike the surface; it stays an island only if
     * that wider region is still small and doesn't reach the edge of the picture.
     */
    private boolean isolated(int id, int minX, int minY, int maxX, int maxY, int maxW, int maxH, int maxArea) {
        extStamp++;
        int head = 0, tail = 0;
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                int i = y * w + x;
                if (label[i] == id) {
                    ext[i] = extStamp;
                    queue[tail++] = i;
                }
            }
        }
        int x0 = minX, x1 = maxX, y0 = minY, y1 = maxY;
        int limit = maxArea * 2;
        while (head < tail) {
            int i = queue[head++];
            int x = i % w, y = i / w;
            if (x == 0 || y == 0 || x == w - 1 || y == h - 1) return false;
            if (x < x0) x0 = x;
            if (x > x1) x1 = x;
            if (y < y0) y0 = y;
            if (y > y1) y1 = y;
            if (tail > limit || x1 - x0 >= maxW || y1 - y0 >= maxH) return false;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int j = i + dy * w + dx;
                    if (ext[j] != extStamp && z[j] > FAINT) {
                        ext[j] = extStamp;
                        queue[tail++] = j;
                    }
                }
            }
        }
        return true;
    }

    private static final int[][] AXES = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};
    private static final float LINE_MIN = 1.6f;

    /**
     * True when the region is just the brightest bit of a line that carries on past it on both
     * sides: a seam, a stitch, a crack in the concrete or a table edge. Something lying on such a
     * line still counts if it stands out, on average, far more than the line itself.
     */
    private boolean onLine(int px, int py, int minX, int minY, int maxX, int maxY, float strength) {
        float minLevel = Math.max(LINE_MIN, threshold() * 0.5f);
        int len = Math.min(20, Math.max(6, 2 * Math.max(maxX - minX + 1, maxY - minY + 1)));
        for (int[] axis : AXES) {
            float ahead = rayLevel(px, py, axis[0], axis[1], minX, minY, maxX, maxY, len);
            float behind = rayLevel(px, py, -axis[0], -axis[1], minX, minY, maxX, maxY, len);
            // A ray that runs straight off the frame can't be judged; the other side decides alone.
            if (ahead < 0f && behind < 0f) continue;
            float level = ahead < 0f ? behind : behind < 0f ? ahead : Math.min(ahead, behind);
            if (level >= minLevel && level * 2.5f > strength) return true;
        }
        return false;
    }

    /**
     * Median, along a ray leaving the box, of the strongest cell across a 3-wide band;
     * -1 when the frame edge comes too soon to tell.
     */
    private float rayLevel(float cx, float cy, int dx, int dy, int minX, int minY, int maxX, int maxY, int len) {
        float[] vals = new float[len];
        int count = 0;
        float x = cx, y = cy;
        while (x >= minX && x <= maxX && y >= minY && y <= maxY) {
            x += dx;
            y += dy;
        }
        for (int k = 0; k < len; k++, x += dx, y += dy) {
            float best = -1f;
            for (int o = -1; o <= 1; o++) {
                int sx = Math.round(x) - dy * o, sy = Math.round(y) + dx * o;
                if (sx < 0 || sx >= w || sy < 0 || sy >= h) continue;
                best = Math.max(best, z[sy * w + sx]);
            }
            if (best < 0f) break;
            vals[count++] = best;
        }
        if (count < 3) return -1f;
        Arrays.sort(vals, 0, count);
        return vals[count / 2];
    }
}
