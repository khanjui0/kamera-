package com.kamera.bulucu;

import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Random;

import org.junit.Test;

/**
 * Drops typical lost things at random spots on many kinds of surface and checks how often each
 * is found, and that the bare surface doesn't produce boxes. The bars are set per surface:
 * a black earring on black rubber or a white pill on snow is camouflaged, so those aren't asked for.
 */
public class SurfacesTest {

    private static final int W = Surfaces.W, H = Surfaces.H, SEEDS = 20;

    /** radius, Y, U, V */
    private static final float[][] THINGS = {
            {1.6f, 170, -20, 25},  // gold earring
            {2f, 215, 0, 0},       // silver ring
            {1.6f, 25, 0, 0},      // black hair tie
            {2.2f, 240, 0, 0},     // white pill
            {1.6f, 95, 32, -18},   // blue bead
            {2f, 75, -8, 48},      // red button
    };
    private static final String[] NAMES = {"gold", "silver", "black", "white pill", "blue bead", "red button"};

    private static void disc(float[][] img, float cx, float cy, float r, float y, float u, float v) {
        for (int yy = (int) (cy - r - 1); yy <= cy + r + 1; yy++) {
            for (int x = (int) (cx - r - 1); x <= cx + r + 1; x++) {
                if (x < 0 || x >= W || yy < 0 || yy >= H) continue;
                if ((x - cx) * (x - cx) + (yy - cy) * (yy - cy) > r * r) continue;
                int i = yy * W + x;
                img[0][i] = y;
                img[1][i] = u;
                img[2][i] = v;
            }
        }
    }

    /**
     * @param minHit per thing, the share of drops (percent) that must be found; 0 = not asked
     * @param maxFalse boxes allowed on the bare surface over all seeds (the tracker hides one-frame flickers)
     */
    private static void check(String name, Surfaces.Surface surface, int maxFalse, int... minHit) {
        FloorDetector d = new FloorDetector(W, H);
        int falses = 0;
        int[] hits = new int[THINGS.length];
        for (long seed = 1; seed <= SEEDS; seed++) {
            float[][] bare = surface.make(seed);
            falses += d.detect(bare[0], bare[1], bare[2]).size();
            Random r = new Random(seed * 31);
            for (int k = 0; k < THINGS.length; k++) {
                float[][] img = surface.make(seed);
                float cx = 10 + r.nextFloat() * 100, cy = 10 + r.nextFloat() * 140;
                float[] t = THINGS[k];
                disc(img, cx, cy, t[0], t[1], t[2], t[3]);
                List<FloorDetector.Box> boxes = d.detect(img[0], img[1], img[2]);
                for (FloorDetector.Box b : boxes) {
                    float px = cx / W, py = cy / H, m = 0.01f;
                    if (px >= b.left - m && px <= b.right + m && py >= b.top - m && py <= b.bottom + m) {
                        hits[k]++;
                        break;
                    }
                }
            }
        }
        assertTrue(name + ": " + falses + " false boxes", falses <= maxFalse);
        for (int k = 0; k < THINGS.length; k++) {
            int pct = hits[k] * 100 / SEEDS;
            assertTrue(name + ", " + NAMES[k] + ": found " + pct + "%", pct >= minHit[k]);
        }
    }

    // gold, silver, black, white pill, blue bead, red button

    @Test
    public void carpet() {
        check("carpet", s -> FloorDetectorTest.carpet(s, 110f, -8f, 12f), 1, 90, 90, 90, 90, 90, 90);
    }

    @Test
    public void concrete() {
        check("concrete", FloorDetectorTest::concrete, 1, 90, 90, 90, 90, 90, 90);
    }

    @Test
    public void woodenTable() {
        check("wood", FloorDetectorTest::wood, 2, 90, 90, 90, 90, 90, 90);
    }

    @Test
    public void carSeat() {
        check("car seat", FloorDetectorTest::carSeat, 2, 90, 90, 0, 90, 90, 90);
    }

    @Test
    public void tiles() {
        check("tiles", Surfaces::tiles, 2, 90, 25, 90, 75, 90, 75);
    }

    @Test
    public void polkaDots() {
        check("polka dots", Surfaces::polkaDots, 15, 55, 0, 90, 0, 85, 90);
    }

    @Test
    public void gravel() {
        check("gravel", Surfaces::gravel, 3, 90, 50, 70, 70, 90, 90);
    }

    @Test
    public void grass() {
        check("grass", Surfaces::grass, 5, 90, 90, 90, 90, 90, 90);
    }

    @Test
    public void parquet() {
        check("parquet", Surfaces::parquet, 2, 90, 90, 90, 90, 90, 90);
    }

    @Test
    public void marble() {
        check("marble", Surfaces::marble, 2, 35, 45, 45, 65, 65, 65);
    }

    @Test
    public void asphalt() {
        check("asphalt", Surfaces::asphalt, 2, 90, 90, 50, 90, 90, 90);
    }

    @Test
    public void sand() {
        check("sand", Surfaces::sand, 2, 90, 90, 90, 90, 90, 90);
    }

    @Test
    public void mosaic() {
        check("mosaic", Surfaces::mosaic, 3, 80, 30, 75, 85, 0, 85);
    }

    @Test
    public void checkerboard() {
        check("checkerboard", Surfaces::checkerboard, 8, 75, 0, 0, 15, 85, 70);
    }

    @Test
    public void stripes() {
        check("stripes", Surfaces::stripes, 2, 90, 30, 90, 70, 80, 85);
    }

    @Test
    public void darkRubber() {
        check("dark rubber", Surfaces::darkRubber, 2, 90, 90, 0, 90, 90, 90);
    }

    @Test
    public void snow() {
        check("snow", Surfaces::snow, 2, 90, 90, 90, 90, 90, 90);
    }

    @Test
    public void terrazzo() {
        check("terrazzo", Surfaces::terrazzo, 28, 40, 55, 0, 60, 15, 20);
    }

    @Test
    public void coarseMat() {
        check("coarse mat", Surfaces::coarseMat, 2, 90, 90, 85, 90, 90, 90);
    }

    @Test
    public void kilimDoesNotFillWithBoxes() {
        // Dense, high-contrast prints are the hardest case; at least they mustn't light up.
        check("kilim", Surfaces::kilim, 3, 0, 0, 0, 0, 0, 0);
    }
}
