package io.github.mustafagulturk.c2map.record;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mustafagulturk.c2map.net.Channel;
import io.github.mustafagulturk.c2map.net.DatagramSink;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordReplayTest {

    @TempDir
    Path dir;

    record Received(Channel channel, byte[] data, long timeMs, long nanos) {
    }

    /** Thread-safe collecting sink that copies the payload (the caller may reuse it). */
    static final class Collector implements DatagramSink {
        final List<Received> items = new ArrayList<>();

        @Override
        public synchronized void accept(Channel channel, byte[] data, long timeMs) {
            items.add(new Received(channel, data.clone(), timeMs, System.nanoTime()));
        }

        synchronized List<Received> items() {
            return List.copyOf(items);
        }
    }

    private static byte[] payload(Random rnd, int len) {
        byte[] b = new byte[len];
        rnd.nextBytes(b);
        return b;
    }

    /** Records {@code n} datagrams at {@code stepMs} intervals starting at {@code t0}. */
    private List<Received> record(Path file, int n, long t0, long stepMs) throws IOException {
        Collector live = new Collector();
        Recorder rec = new Recorder(live, () -> 1_000L);
        rec.start(file);
        Random rnd = new Random(7);
        List<Received> sent = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Channel ch = i % 3 == 0 ? Channel.TRACKS : Channel.RADAR_PLOTS;
            byte[] data = payload(rnd, i == 5 ? 0 : 1 + rnd.nextInt(1500));
            long t = t0 + i * stepMs;
            rec.accept(ch, data, t);
            sent.add(new Received(ch, data, t, 0));
        }
        assertEquals(n, live.items().size());
        assertEquals(n, rec.datagramCount());
        rec.stop();
        assertEquals(Files.size(file), rec.byteCount());
        return sent;
    }

    private static void assertSame(List<Received> expected, List<Received> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).channel(), actual.get(i).channel(), "channel " + i);
            assertEquals(expected.get(i).timeMs(), actual.get(i).timeMs(), "time " + i);
            assertArrayEquals(expected.get(i).data(), actual.get(i).data(), "data " + i);
        }
    }

    @Test
    void recorderPassesThroughWhenNotRecording() throws IOException {
        Collector live = new Collector();
        Recorder rec = new Recorder(live);
        assertFalse(rec.isRecording());
        rec.accept(Channel.TRACKS, new byte[] {1}, 5);
        rec.stop();
        assertEquals(1, live.items().size());
        assertEquals(0, rec.datagramCount());
    }

    @Test
    void infoAndHeader() throws IOException {
        Path f = dir.resolve("a.c2rec");
        List<Received> sent = record(f, 30, 1_700_000_000_000L, 100);
        byte[] head = Arrays.copyOf(Files.readAllBytes(f), 6);
        assertEquals("C2REC1", new String(head, java.nio.charset.StandardCharsets.US_ASCII));
        RecordingInfo info = RecordingInfo.read(f);
        assertEquals(1, info.version());
        assertEquals(1_000L, info.startEpochMs());
        assertEquals(30, info.datagramCount());
        assertEquals(10, info.count(Channel.TRACKS));
        assertEquals(20, info.count(Channel.RADAR_PLOTS));
        assertEquals(2900, info.durationMs());
        assertEquals(sent.get(0).timeMs(), info.firstTimeMs());
        assertFalse(info.truncated());
    }

    @Test
    void roundTripAtHighSpeedIsByteExactAndOrdered() throws Exception {
        Path f = dir.resolve("b.c2rec");
        List<Received> sent = record(f, 200, 1_700_000_000_000L, 10); // 1.99 s of data
        Collector out = new Collector();
        Player p = new Player(out);
        CountDownLatch done = new CountDownLatch(1);
        p.setOnFinished(done::countDown);
        RecordingInfo info = p.open(f);
        assertEquals(1990, info.durationMs());
        p.setSpeed(100); // clamped to 16x
        assertEquals(Player.MAX_SPEED, p.speed());
        long t0 = System.nanoTime();
        p.play();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        long wallMs = (System.nanoTime() - t0) / 1_000_000;
        assertSame(sent, out.items());
        assertEquals(Player.State.FINISHED, p.state());
        assertEquals(1.0, p.progress());
        assertEquals(info.lastTimeMs(), p.currentDataTimeMs());
        // 1990 ms / 16 = 124 ms
        assertTrue(wallMs >= 110, "too fast: " + wallMs);
        assertTrue(wallMs < 1500, "too slow: " + wallMs);
    }

    @Test
    void timingScaleIsRoughlyRespected() throws Exception {
        Path f = dir.resolve("c.c2rec");
        record(f, 9, 0, 100); // 800 ms of data
        Collector out = new Collector();
        Player p = new Player(out);
        CountDownLatch done = new CountDownLatch(1);
        p.setOnFinished(done::countDown);
        p.open(f);
        p.setSpeed(2);
        p.play();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        List<Received> items = out.items();
        double wallMs = (items.get(8).nanos() - items.get(0).nanos()) / 1e6;
        assertEquals(400, wallMs, 120, "800 ms of data at 2x");
        // Datagram i is due 50 ms * i after the first. The player schedules against absolute
        // times, so a late wake-up is caught up on the next datagram rather than accumulating.
        for (int i = 1; i < items.size(); i++) {
            double at = (items.get(i).nanos() - items.get(0).nanos()) / 1e6;
            assertEquals(50.0 * i, at, 80, "datagram " + i);
        }
    }

    @Test
    void pauseResumeStopAndSpeedChange() throws Exception {
        Path f = dir.resolve("d.c2rec");
        record(f, 41, 10_000, 100); // 4 s
        Collector out = new Collector();
        Player p = new Player(out);
        p.open(f);
        p.setSpeed(4);
        p.play();
        Thread.sleep(200); // ~800 ms of data
        p.pause();
        assertEquals(Player.State.PAUSED, p.state());
        int n = out.items().size();
        long pos = p.currentDataTimeMs();
        assertTrue(n >= 3 && n <= 15, "delivered " + n);
        assertTrue(pos >= 10_000 && pos < 12_000, "pos " + pos);
        Thread.sleep(150);
        assertEquals(n, out.items().size());
        assertEquals(pos, p.currentDataTimeMs());
        double prog = p.progress();
        assertTrue(prog > 0 && prog < 0.5, "progress " + prog);

        p.setSpeed(16);
        p.play();
        Thread.sleep(50);
        assertTrue(p.currentDataTimeMs() > pos);
        p.stop();
        int afterStop = out.items().size();
        Thread.sleep(100);
        assertEquals(afterStop, out.items().size());
        assertEquals(Player.State.STOPPED, p.state());
        assertEquals(10_000, p.currentDataTimeMs());
        assertEquals(0.0, p.progress());

        // Play again from the beginning, to the end.
        CountDownLatch done = new CountDownLatch(1);
        p.setOnFinished(done::countDown);
        p.play();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(afterStop + 41, out.items().size());
        assertEquals(10_000, out.items().get(afterStop).timeMs());
    }

    @Test
    void truncatedFileIsHandledGracefully() throws Exception {
        Path f = dir.resolve("e.c2rec");
        List<Received> sent = record(f, 10, 0, 1);
        byte[] all = Files.readAllBytes(f);
        Path cut = dir.resolve("cut.c2rec");
        Files.write(cut, Arrays.copyOf(all, all.length - 3)); // last payload incomplete
        RecordingInfo info = RecordingInfo.read(cut);
        assertTrue(info.truncated());
        assertEquals(9, info.datagramCount());

        Collector out = new Collector();
        Player p = new Player(out);
        CountDownLatch done = new CountDownLatch(1);
        p.setOnFinished(done::countDown);
        p.open(cut);
        p.setSpeed(16);
        p.play();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertSame(sent.subList(0, 9), out.items());
        assertNull(p.lastError());

        // Cut inside a record's time field.
        Files.write(cut, Arrays.copyOf(all, RecordingFormat.HEADER_SIZE + 4));
        assertEquals(0, RecordingInfo.read(cut).datagramCount());
        assertTrue(RecordingInfo.read(cut).truncated());

        // Header only.
        Files.write(cut, Arrays.copyOf(all, RecordingFormat.HEADER_SIZE));
        RecordingInfo empty = RecordingInfo.read(cut);
        assertEquals(0, empty.datagramCount());
        assertFalse(empty.truncated());

        // Broken header / wrong magic.
        Files.write(cut, Arrays.copyOf(all, 10));
        assertThrows(IOException.class, () -> RecordingInfo.read(cut));
        Files.write(cut, "NOTAREC-12345678xxxxxxxxxxxx".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertThrows(IOException.class, () -> p.open(cut));
    }

    @Test
    void concurrentRecordingFromSeveralThreads() throws Exception {
        Path f = dir.resolve("f.c2rec");
        AtomicLong live = new AtomicLong();
        Recorder rec = new Recorder((c, d, t) -> live.incrementAndGet());
        rec.start(f);
        List<Thread> ts = new ArrayList<>();
        for (int k = 0; k < 4; k++) {
            Channel ch = k % 2 == 0 ? Channel.RADAR_PLOTS : Channel.TRACKS;
            int id = k;
            Thread th = new Thread(() -> {
                for (int i = 0; i < 2500; i++) {
                    byte[] b = new byte[16];
                    Arrays.fill(b, (byte) id);
                    rec.accept(ch, b, i);
                }
            });
            ts.add(th);
            th.start();
        }
        for (Thread th : ts) {
            th.join();
        }
        rec.stop();
        assertEquals(10_000, live.get());
        RecordingInfo info = RecordingInfo.read(f);
        assertEquals(10_000, info.datagramCount());
        assertEquals(5_000, info.count(Channel.TRACKS));
        assertFalse(info.truncated());
        assertEquals(RecordingFormat.HEADER_SIZE + 10_000L * (RecordingFormat.RECORD_OVERHEAD + 16), Files.size(f));
    }

    @Test
    void oversizedDatagramIsDroppedButForwarded() throws IOException {
        Collector live = new Collector();
        Recorder rec = new Recorder(live);
        rec.start(dir.resolve("g.c2rec"));
        rec.accept(Channel.RADAR_PLOTS, new byte[70_000], 1);
        rec.accept(Channel.RADAR_PLOTS, new byte[RecordingFormat.MAX_PAYLOAD], 2);
        rec.stop();
        assertEquals(2, live.items().size());
        assertEquals(1, rec.droppedCount());
        assertEquals(1, rec.datagramCount());
        assertEquals(1, RecordingInfo.read(dir.resolve("g.c2rec")).datagramCount());
    }

    @Test
    void playWithoutOpenFails() {
        assertThrows(IllegalStateException.class, () -> new Player((c, d, t) -> { }).play());
    }
}
