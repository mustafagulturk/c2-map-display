package io.github.mustafagulturk.c2map.map;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FrameStatsTest {

    private static final long MS = 1_000_000L;

    @Test
    void emptyStatsAreZero() {
        FrameStats s = new FrameStats(10);
        assertEquals(0, s.averageDrawMs());
        assertEquals(0, s.p99DrawMs());
        assertEquals(0, s.fps());
    }

    @Test
    void averagePercentileAndFps() {
        FrameStats s = new FrameStats(100);
        // 100 frames, 16.666 ms apart (60 FPS); draw times 1..100 ms.
        for (int i = 0; i < 100; i++) {
            s.record(i * 16_666_667L, (i + 1) * MS);
        }
        assertEquals(50.5, s.averageDrawMs(), 1e-9);
        assertEquals(99.0, s.p99DrawMs(), 1e-9);
        assertEquals(50.0, s.percentileDrawMs(50), 1e-9);
        assertEquals(100.0, s.maxDrawMs(), 1e-9);
        assertEquals(60.0, s.fps(), 0.01);
    }

    @Test
    void ringBufferKeepsOnlyRecentFrames() {
        FrameStats s = new FrameStats(4);
        for (int i = 0; i < 10; i++) {
            s.record(i * 100 * MS, (i < 6 ? 50 : 2) * MS); // last 4 frames are 2 ms, 10 FPS
        }
        assertEquals(4, s.size());
        assertEquals(10, s.totalFrames());
        assertEquals(2.0, s.averageDrawMs(), 1e-9);
        assertEquals(10.0, s.fps(), 1e-9);
        s.reset();
        assertEquals(0, s.size());
    }
}
