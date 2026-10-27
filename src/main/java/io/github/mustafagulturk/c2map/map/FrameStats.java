package io.github.mustafagulturk.c2map.map;

import java.util.Arrays;

/**
 * Ring buffer of recent frame timings: how long each redraw took and when it started.
 * Thread-safe, so a status bar or benchmark can read it while the FX thread records.
 */
public final class FrameStats {

    /** Default number of frames kept (10 s at 60 FPS). */
    public static final int DEFAULT_CAPACITY = 600;

    private final long[] startNs;
    private final long[] drawNs;
    private int next;
    private int size;
    private long total;

    public FrameStats() {
        this(DEFAULT_CAPACITY);
    }

    public FrameStats(int capacity) {
        if (capacity < 2) {
            throw new IllegalArgumentException("capacity must be at least 2");
        }
        startNs = new long[capacity];
        drawNs = new long[capacity];
    }

    /** Records one frame that started at {@code frameStartNs} (System.nanoTime) and took {@code durationNs}. */
    public synchronized void record(long frameStartNs, long durationNs) {
        startNs[next] = frameStartNs;
        drawNs[next] = durationNs;
        next = (next + 1) % startNs.length;
        size = Math.min(size + 1, startNs.length);
        total++;
    }

    /** Frames currently in the buffer. */
    public synchronized int size() {
        return size;
    }

    /** Frames recorded since creation or the last {@link #reset()}. */
    public synchronized long totalFrames() {
        return total;
    }

    /** Mean draw time over the buffer, milliseconds (0 when empty). */
    public synchronized double averageDrawMs() {
        if (size == 0) {
            return 0;
        }
        long sum = 0;
        for (int i = 0; i < size; i++) {
            sum += drawNs[i];
        }
        return sum / (double) size / 1e6;
    }

    /** 99th percentile draw time over the buffer, milliseconds (nearest-rank; 0 when empty). */
    public synchronized double p99DrawMs() {
        return percentileDrawMs(99);
    }

    /** Nearest-rank percentile of the draw time, milliseconds. */
    public synchronized double percentileDrawMs(double percentile) {
        if (size == 0) {
            return 0;
        }
        long[] sorted = Arrays.copyOf(drawNs, size);
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(percentile / 100.0 * size);
        return sorted[Math.max(0, Math.min(size - 1, rank - 1))] / 1e6;
    }

    /** Longest draw time in the buffer, milliseconds. */
    public synchronized double maxDrawMs() {
        long max = 0;
        for (int i = 0; i < size; i++) {
            max = Math.max(max, drawNs[i]);
        }
        return max / 1e6;
    }

    /** Frames per second over the span of the buffer (0 with fewer than two frames). */
    public synchronized double fps() {
        if (size < 2) {
            return 0;
        }
        int newest = (next - 1 + startNs.length) % startNs.length;
        int oldest = size < startNs.length ? 0 : next;
        long span = startNs[newest] - startNs[oldest];
        return span <= 0 ? 0 : (size - 1) * 1e9 / span;
    }

    public synchronized void reset() {
        next = 0;
        size = 0;
        total = 0;
    }

    @Override
    public synchronized String toString() {
        return String.format(java.util.Locale.ROOT, "%.1f FPS, draw avg %.2f ms, p99 %.2f ms (%d frames)",
                fps(), averageDrawMs(), p99DrawMs(), size);
    }
}
