package com.kamera.bulucu;

import java.util.Random;

/**
 * Synthetic camera grids (120x160, Y 0..255, U/V centred on 0) of surfaces something might be
 * dropped on. Each has its own way of fooling a detector: repeating motifs, lines, grain, glare.
 */
final class Surfaces {

    static final int W = 120, H = 160;

    interface Surface {
        float[][] make(long seed);
    }

    private Surfaces() {
    }

    private static float[][] blank() {
        return new float[][]{new float[W * H], new float[W * H], new float[W * H]};
    }

    private static void set(float[][] img, int x, int y, float yy, float u, float v) {
        if (x < 0 || x >= W || y < 0 || y >= H) return;
        int i = y * W + x;
        img[0][i] = yy;
        img[1][i] = u;
        img[2][i] = v;
    }

    private static void noise(float[][] img, Random r, float sy, float sc) {
        for (int i = 0; i < W * H; i++) {
            img[0][i] += (float) r.nextGaussian() * sy;
            img[1][i] += (float) r.nextGaussian() * sc;
            img[2][i] += (float) r.nextGaussian() * sc;
        }
    }

    private static void light(float[][] img, Random r) {
        float gx = (r.nextFloat() - 0.5f) * 0.5f, gy = (r.nextFloat() - 0.5f) * 0.5f;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                img[0][y * W + x] *= 1f + gx * (x - W / 2f) / W + gy * (y - H / 2f) / H;
            }
        }
    }

    private static void disc(float[][] img, float cx, float cy, float rad, float y, float u, float v) {
        for (int yy = (int) (cy - rad - 1); yy <= cy + rad + 1; yy++) {
            for (int x = (int) (cx - rad - 1); x <= cx + rad + 1; x++) {
                if ((x - cx) * (x - cx) + (yy - cy) * (yy - cy) <= rad * rad) set(img, x, yy, y, u, v);
            }
        }
    }

    /** White glazed tiles with grey grout lines and a soft glare spot. */
    static float[][] tiles(long seed) {
        Random r = new Random(seed);
        float[][] img = blank();
        int size = 18 + r.nextInt(8), ox = r.nextInt(size), oy = r.nextInt(size);
        float gx = r.nextFloat() * W, gy = r.nextFloat() * H;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                boolean grout = (x + ox) % size == 0 || (y + oy) % size == 0;
                float d2 = (x - gx) * (x - gx) + (y - gy) * (y - gy);
                float glare = 35f * (float) Math.exp(-d2 / 900f);
                set(img, x, y, (grout ? 150f : 205f) + glare, 0f, grout ? 3f : 0f);
            }
        }
        noise(img, r, 2.5f, 1f);
        light(img, r);
        return img;
    }

    /** Polka-dot fabric: a regular grid of contrasting dots. */
    static float[][] polkaDots(long seed) {
        Random r = new Random(seed);
        float[][] img = blank();
        for (int i = 0; i < W * H; i++) {
            img[0][i] = 60f;
            img[1][i] = 20f;
            img[2][i] = -12f;
        }
        int step = 9 + r.nextInt(4);
        float ox = r.nextFloat() * step, oy = r.nextFloat() * step;
        for (float y = oy; y < H + step; y += step) {
            for (float x = ox + ((int) (y / step) % 2) * step / 2f; x < W + step; x += step) {
                disc(img, x, y, 1.6f, 220f, 0f, 0f);
            }
        }
        noise(img, r, 3f, 1.5f);
        light(img, r);
        return img;
    }

    /** A busy kilim/floral print: a tiled motif in three colours with a little jitter. */
    static float[][] kilim(long seed) {
        Random r = new Random(seed);
        float[][] img = blank();
        for (int i = 0; i < W * H; i++) {
            img[0][i] = 70f;
            img[1][i] = -5f;
            img[2][i] = 40f;
        }
        int step = 14;
        for (int ty = -1; ty < H / step + 2; ty++) {
            for (int tx = -1; tx < W / step + 2; tx++) {
                float cx = tx * step + 7 + r.nextFloat() - 0.5f, cy = ty * step + 7 + r.nextFloat() - 0.5f;
                for (int k = -4; k <= 4; k++) {
                    set(img, Math.round(cx + k), Math.round(cy), 150f, -20f, 10f);
                    set(img, Math.round(cx), Math.round(cy + k), 150f, -20f, 10f);
                }
                disc(img, cx, cy, 1.5f, 200f, 5f, 25f);
                disc(img, cx + 7, cy + 7, 1.2f, 40f, 15f, -10f);
            }
        }
        noise(img, r, 5f, 2f);
        light(img, r);
        return img;
    }

    /** Gravel: many pebbles of different greys and sizes. */
    static float[][] gravel(long seed) {
        Random r = new Random(seed);
        float[][] img = blank();
        for (int i = 0; i < W * H; i++) img[0][i] = 110f;
        for (int k = 0; k < 900; k++) {
            float g = 70f + r.nextFloat() * 90f;
            disc(img, r.nextFloat() * W, r.nextFloat() * H, 1f + r.nextFloat() * 2.5f, g, (r.nextFloat() - 0.5f) * 6f, 4f + (r.nextFloat() - 0.5f) * 6f);
        }
        noise(img, r, 4f, 1f);
        light(img, r);
        return img;
    }

    /** Lawn: thin, mostly upright blades in shades of green. */
    static float[][] grass(long seed) {
        Random r = new Random(seed);
        float[][] img = blank();
        for (int i = 0; i < W * H; i++) {
            img[0][i] = 80f;
            img[1][i] = -18f;
            img[2][i] = -14f;
        }
        for (int k = 0; k < 700; k++) {
            float x = r.nextFloat() * W, y = r.nextFloat() * H, lean = (r.nextFloat() - 0.5f) * 0.6f;
            float g = 60f + r.nextFloat() * 70f;
            int len = 6 + r.nextInt(10);
            for (int s = 0; s < len; s++) set(img, Math.round(x + lean * s), Math.round(y + s), g, -22f, -18f + g * 0.05f);
        }
        noise(img, r, 4f, 2f);
        light(img, r);
        return img;
    }

    /** Herringbone parquet: wood grain in blocks that alternate direction. */
    static float[][] parquet(long seed) {
        Random r = new Random(seed);
        float[][] img = blank();
        int bw = 24, bh = 8;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int bx = x / bw, by = y / bh;
                boolean flip = (bx + by) % 2 == 0;
                double t = flip ? y * 1.1 + Math.sin(x * 0.2) : x * 0.5 + Math.sin(y * 0.4);
                float tone = ((bx * 7 + by * 13) % 5) * 6f;
                boolean joint = x % bw == 0 || y % bh == 0;
                float g = 115f + tone + 10f * (float) Math.sin(t) - (joint ? 25f : 0f);
                set(img, x, y, g, -12f, 20f + tone * 0.2f);
            }
        }
        noise(img, r, 3f, 1.5f);
        light(img, r);
        return img;
    }

    /** Polished marble: pale with thin wandering veins. */
    static float[][] marble(long seed) {
        Random r = new Random(seed);
        float[][] img = blank();
        for (int i = 0; i < W * H; i++) {
            img[0][i] = 200f;
            img[1][i] = 1f;
            img[2][i] = 3f;
        }
        for (int k = 0; k < 6; k++) {
            float x = r.nextFloat() * W, y = 0, dx = (r.nextFloat() - 0.5f) * 1.5f;
            float g = 120f + r.nextFloat() * 50f;
            for (int s = 0; s < H; s++) {
                set(img, Math.round(x), Math.round(y), g, 0f, 3f);
                x += dx + (r.nextFloat() - 0.5f) * 1.2f;
                y += 1f;
            }
        }
        noise(img, r, 2f, 0.8f);
        light(img, r);
        return img;
    }

    /** Asphalt: dark, grainy, with many small light stone specks. */
    static float[][] asphalt(long seed) {
        Random r = new Random(seed);
        float[][] img = blank();
        for (int i = 0; i < W * H; i++) img[0][i] = 55f;
        for (int k = 0; k < 600; k++) disc(img, r.nextFloat() * W, r.nextFloat() * H, 0.5f + r.nextFloat(), 90f + r.nextFloat() * 40f, 0f, 2f);
        noise(img, r, 6f, 1.5f);
        light(img, r);
        return img;
    }

    /** Beach sand: fine grain with wind ripples. */
    static float[][] sand(long seed) {
        Random r = new Random(seed);
        float[][] img = blank();
        double a = r.nextDouble() * Math.PI;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                double t = (x * Math.cos(a) + y * Math.sin(a)) * 0.6;
                set(img, x, y, 175f + 12f * (float) Math.sin(t + Math.sin(y * 0.05) * 2), -6f, 18f);
            }
        }
        noise(img, r, 6f, 2f);
        light(img, r);
        return img;
    }

    /** Small square mosaic tiles in a handful of colours, with grout. */
    static float[][] mosaic(long seed) {
        Random r = new Random(seed);
        float[][] img = blank();
        float[][] palette = {{90, 25, -15}, {120, 18, -8}, {150, 8, -4}, {70, 30, -20}};
        int s = 6;
        for (int ty = 0; ty <= H / s; ty++) {
            for (int tx = 0; tx <= W / s; tx++) {
                float[] c = palette[r.nextInt(palette.length)];
                for (int y = ty * s; y < ty * s + s; y++) {
                    for (int x = tx * s; x < tx * s + s; x++) {
                        boolean grout = y == ty * s || x == tx * s;
                        set(img, x, y, grout ? 170f : c[0], grout ? 0f : c[1], grout ? 0f : c[2]);
                    }
                }
            }
        }
        noise(img, r, 3f, 1.5f);
        light(img, r);
        return img;
    }

    /** Large black-and-white checkerboard floor. */
    static float[][] checkerboard(long seed) {
        Random r = new Random(seed);
        float[][] img = blank();
        int s = 30 + r.nextInt(10), ox = r.nextInt(s), oy = r.nextInt(s);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                boolean dark = (((x + ox) / s) + ((y + oy) / s)) % 2 == 0;
                set(img, x, y, dark ? 35f : 215f, 0f, 0f);
            }
        }
        noise(img, r, 3f, 1f);
        light(img, r);
        return img;
    }

    /** Striped bed sheet or towel. */
    static float[][] stripes(long seed) {
        Random r = new Random(seed);
        float[][] img = blank();
        int period = 8 + r.nextInt(8);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                boolean band = (x + y / 3) % period < period / 2;
                set(img, x, y, band ? 190f : 120f, band ? 0f : 22f, band ? 0f : -12f);
            }
        }
        noise(img, r, 4f, 1.5f);
        light(img, r);
        return img;
    }

    /** Black rubber gym mat in poor light: dark and noisy. */
    static float[][] darkRubber(long seed) {
        Random r = new Random(seed);
        float[][] img = blank();
        for (int i = 0; i < W * H; i++) img[0][i] = 22f;
        noise(img, r, 5f, 3f);
        light(img, r);
        return img;
    }

    /** Snow or a white sheet: bright, almost featureless. */
    static float[][] snow(long seed) {
        Random r = new Random(seed);
        float[][] img = blank();
        for (int i = 0; i < W * H; i++) {
            img[0][i] = 235f;
            img[1][i] = 3f;
            img[2][i] = -2f;
        }
        noise(img, r, 2f, 0.7f);
        light(img, r);
        return img;
    }

    /** Terrazzo: pale base with scattered chips of many colours. */
    static float[][] terrazzo(long seed) {
        Random r = new Random(seed);
        float[][] img = blank();
        for (int i = 0; i < W * H; i++) {
            img[0][i] = 190f;
            img[1][i] = 0f;
            img[2][i] = 4f;
        }
        float[][] chips = {{120, -10, 25}, {80, 5, 5}, {150, 15, -10}, {100, -5, -5}};
        for (int k = 0; k < 260; k++) {
            float[] c = chips[r.nextInt(chips.length)];
            disc(img, r.nextFloat() * W, r.nextFloat() * H, 0.8f + r.nextFloat() * 1.4f, c[0], c[1], c[2]);
        }
        noise(img, r, 3f, 1.2f);
        light(img, r);
        return img;
    }

    /** Car-boot carpet / doormat: coarse loops in two tones. */
    static float[][] coarseMat(long seed) {
        Random r = new Random(seed);
        float[][] img = blank();
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                boolean loop = ((x / 2) + (y / 2)) % 2 == 0;
                set(img, x, y, loop ? 70f : 45f, 4f, 6f);
            }
        }
        noise(img, r, 8f, 2f);
        light(img, r);
        return img;
    }
}
