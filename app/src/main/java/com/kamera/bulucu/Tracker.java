package com.kamera.bulucu;

import java.util.ArrayList;
import java.util.List;

/**
 * Smooths detections over time so a box only appears once something has been seen in several
 * frames in a row, stays put instead of jittering, and doesn't blink out on a single missed frame.
 */
public final class Tracker {

    public static final class Track {
        public float left, top, right, bottom;
        int hits, misses;
        boolean announced;

        public boolean visible() {
            return hits >= CONFIRM_HITS && misses <= KEEP_MISSES;
        }
    }

    static final int CONFIRM_HITS = 3;
    static final int KEEP_MISSES = 3;
    private static final int DROP_MISSES = 6;
    private static final float SMOOTH = 0.5f;

    private final List<Track> tracks = new ArrayList<>();

    public void reset() {
        tracks.clear();
    }

    /** Feeds one frame of detections; returns how many tracks became visible for the first time. */
    public int update(List<FloorDetector.Box> boxes) {
        boolean[] used = new boolean[boxes.size()];
        for (Track t : tracks) {
            int best = -1;
            float bestScore = 0f;
            for (int i = 0; i < boxes.size(); i++) {
                if (used[i]) continue;
                float s = match(t, boxes.get(i));
                if (s > bestScore) {
                    bestScore = s;
                    best = i;
                }
            }
            if (best >= 0) {
                used[best] = true;
                FloorDetector.Box b = boxes.get(best);
                t.left += (b.left - t.left) * SMOOTH;
                t.top += (b.top - t.top) * SMOOTH;
                t.right += (b.right - t.right) * SMOOTH;
                t.bottom += (b.bottom - t.bottom) * SMOOTH;
                t.hits++;
                t.misses = 0;
            } else {
                t.misses++;
            }
        }
        tracks.removeIf(t -> t.misses > DROP_MISSES || (t.hits < CONFIRM_HITS && t.misses > 1));
        for (int i = 0; i < boxes.size(); i++) {
            if (used[i]) continue;
            FloorDetector.Box b = boxes.get(i);
            Track t = new Track();
            t.left = b.left;
            t.top = b.top;
            t.right = b.right;
            t.bottom = b.bottom;
            t.hits = 1;
            tracks.add(t);
        }
        int fresh = 0;
        for (Track t : tracks) {
            if (t.visible() && !t.announced) {
                t.announced = true;
                fresh++;
            }
        }
        return fresh;
    }

    public List<Track> visible() {
        List<Track> out = new ArrayList<>();
        for (Track t : tracks) if (t.visible()) out.add(t);
        return out;
    }

    /** Overlap, or closeness of centres for small boxes that moved a little with the hand. */
    private static float match(Track t, FloorDetector.Box b) {
        float ix = Math.min(t.right, b.right) - Math.max(t.left, b.left);
        float iy = Math.min(t.bottom, b.bottom) - Math.max(t.top, b.top);
        float inter = ix > 0 && iy > 0 ? ix * iy : 0f;
        float union = (t.right - t.left) * (t.bottom - t.top) + (b.right - b.left) * (b.bottom - b.top) - inter;
        float iou = union > 0 ? inter / union : 0f;
        if (iou > 0.1f) return 1f + iou;
        float dx = (t.left + t.right - b.left - b.right) / 2f;
        float dy = (t.top + t.bottom - b.top - b.bottom) / 2f;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        return dist < 0.08f ? 1f - dist / 0.08f : 0f;
    }
}
