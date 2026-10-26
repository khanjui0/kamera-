package com.kamera.bulucu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Random;

import org.junit.Test;

public class FloorDetectorTest {

    private static final int W = 120, H = 160;

    /** A synthetic carpet: fibre noise, a repeating woven pattern and uneven lighting. */
    private static float[][] carpet(long seed, float baseY, float baseU, float baseV) {
        Random r = new Random(seed);
        float[] y = new float[W * H], u = new float[W * H], v = new float[W * H];
        for (int yy = 0; yy < H; yy++) {
            for (int x = 0; x < W; x++) {
                int i = yy * W + x;
                float light = 1f - 0.35f * x / W + 0.15f * yy / H;
                float weave = 9f * (float) (Math.sin(x * 0.9) * Math.sin(yy * 0.9));
                float border = (x / 30 + yy / 30) % 2 == 0 ? 6f : -6f;
                y[i] = (baseY + weave + border + (float) r.nextGaussian() * 7f) * light;
                u[i] = baseU + (float) r.nextGaussian() * 2.5f;
                v[i] = baseV + border * 0.3f + (float) r.nextGaussian() * 2.5f;
            }
        }
        return new float[][]{y, u, v};
    }

    private static void paint(float[][] img, int cx, int cy, int radius, float y, float u, float v) {
        for (int yy = cy - radius; yy <= cy + radius; yy++) {
            for (int x = cx - radius; x <= cx + radius; x++) {
                if ((x - cx) * (x - cx) + (yy - cy) * (yy - cy) > radius * radius) continue;
                int i = yy * W + x;
                img[0][i] = y;
                img[1][i] = u;
                img[2][i] = v;
            }
        }
    }

    private static boolean covers(List<FloorDetector.Box> boxes, int cx, int cy) {
        float px = (cx + 0.5f) / W, py = (cy + 0.5f) / H;
        for (FloorDetector.Box b : boxes) {
            if (px >= b.left && px <= b.right && py >= b.top && py <= b.bottom) return true;
        }
        return false;
    }

    @Test
    public void plainCarpetHasNoBoxes() {
        FloorDetector d = new FloorDetector(W, H);
        for (long seed = 1; seed <= 20; seed++) {
            float[][] img = carpet(seed, 110f, -8f, 12f);
            List<FloorDetector.Box> boxes = d.detect(img[0], img[1], img[2]);
            assertEquals("seed " + seed, 0, boxes.size());
        }
    }

    @Test
    public void findsSmallThingsOnCarpet() {
        FloorDetector d = new FloorDetector(W, H);
        for (long seed = 1; seed <= 20; seed++) {
            float[][] img = carpet(seed, 110f, -8f, 12f);
            paint(img, 30, 40, 2, 30f, 0f, 0f);       // dark earring
            paint(img, 80, 110, 2, 115f, 20f, -10f);  // same brightness, different colour (pill)
            paint(img, 60, 70, 1, 210f, 0f, 0f);      // small shiny bead
            List<FloorDetector.Box> boxes = d.detect(img[0], img[1], img[2]);
            assertTrue("dark, seed " + seed, covers(boxes, 30, 40));
            assertTrue("colour, seed " + seed, covers(boxes, 80, 110));
            assertTrue("bead, seed " + seed, covers(boxes, 60, 70));
            assertTrue("extras, seed " + seed + ": " + boxes.size(), boxes.size() <= 4);
        }
    }

    @Test
    public void ignoresLargeShapes() {
        FloorDetector d = new FloorDetector(W, H);
        float[][] img = carpet(3, 110f, -8f, 12f);
        // A foot or a chair leg's shadow filling a large part of the frame.
        for (int yy = 60; yy < 140; yy++) {
            for (int x = 20; x < 100; x++) img[0][yy * W + x] *= 0.4f;
        }
        paint(img, 10, 20, 2, 25f, 0f, 0f);
        List<FloorDetector.Box> boxes = d.detect(img[0], img[1], img[2]);
        assertTrue(covers(boxes, 10, 20));
        for (FloorDetector.Box b : boxes) {
            assertTrue((b.right - b.left) < 0.35f && (b.bottom - b.top) < 0.35f);
        }
    }

    @Test
    public void trackerNeedsSeveralFramesAndSurvivesADrop() {
        FloorDetector d = new FloorDetector(W, H);
        Tracker t = new Tracker();
        float[][] img = carpet(5, 110f, -8f, 12f);
        paint(img, 50, 50, 2, 30f, 0f, 0f);
        List<FloorDetector.Box> boxes = d.detect(img[0], img[1], img[2]);
        t.update(boxes);
        t.update(boxes);
        assertEquals(0, t.visible().size());
        assertEquals(1, t.update(boxes));
        assertEquals(1, t.visible().size());
        t.update(java.util.Collections.emptyList());
        assertEquals(1, t.visible().size());
    }
}
