package io.github.mustafagulturk.c2map.track;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mustafagulturk.c2map.geo.GeoPoint;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TrackStoreTest {

    private static TrackUpdate radar(int id, double lat) {
        return new TrackUpdate(TrackSnapshot.Source.RADAR, id, new GeoPoint(lat, 27), 100, 90, Identity.UNKNOWN);
    }

    private static TrackUpdate link(int id, Identity identity) {
        return new TrackUpdate(TrackSnapshot.Source.LINK, id, new GeoPoint(38, 27), 50, -10, identity);
    }

    private static final class Recorder implements TrackListener {
        final List<String> events = new ArrayList<>();

        @Override
        public void onTrackUpdated(TrackSnapshot previous, TrackSnapshot current) {
            events.add((previous == null ? "new " : "upd ") + current.key() + " " + current.identity());
        }

        @Override
        public void onTrackDropped(TrackSnapshot last) {
            events.add("drop " + last.key());
        }
    }

    @Test
    void storesTracksAndNotifies() {
        TrackStore store = new TrackStore();
        Recorder rec = new Recorder();
        store.addListener(rec);
        store.apply(List.of(radar(1, 38), link(7, Identity.HOSTILE)), List.of(), 1000);
        store.update(radar(1, 38.01), 2000);

        List<TrackSnapshot> snap = store.snapshot();
        assertEquals(2, snap.size());
        assertThrows(UnsupportedOperationException.class, () -> snap.add(snap.get(0)));
        assertSame(snap, store.snapshot(), "cached while unchanged");
        TrackSnapshot l7 = store.get("L-7").orElseThrow();
        assertEquals(Identity.HOSTILE, l7.identity());
        assertEquals(350.0, l7.courseDeg(), 1e-9);
        assertEquals(Identity.UNKNOWN, store.get("R-1").orElseThrow().identity());
        assertEquals(2000, store.latestTimeMs());
        assertEquals(List.of("new R-1 UNKNOWN", "new L-7 HOSTILE", "upd R-1 UNKNOWN"), rec.events);
    }

    @Test
    void trailIsThrottledAndBounded() {
        TrackStore store = new TrackStore(15_000, 5, 2_000);
        for (int i = 0; i <= 40; i++) {
            store.update(radar(1, 38 + i * 0.001), i * 500L); // every 0.5 s
        }
        TrackSnapshot s = store.get("R-1").orElseThrow();
        assertEquals(5, s.history().size());
        // Oldest first, spaced 2 s (4 updates) apart, last one before the current position.
        assertEquals(38.039, s.history().get(4).lat(), 1e-9);
        assertEquals(38.023, s.history().get(0).lat(), 1e-9);
        assertEquals(38.040, s.position().lat(), 1e-9);
    }

    @Test
    void identityOverride() {
        TrackStore store = new TrackStore();
        Recorder rec = new Recorder();
        store.apply(List.of(radar(3, 38), link(4, Identity.FRIEND)), List.of(), 0);
        store.addListener(rec);

        assertTrue(store.setIdentity("R-3", Identity.HOSTILE));
        assertEquals(Identity.HOSTILE, store.get("R-3").orElseThrow().identity());
        store.update(radar(3, 38.1), 1000);
        assertEquals(Identity.HOSTILE, store.get("R-3").orElseThrow().identity(), "override survives updates");

        store.setIdentity("L-4", Identity.UNKNOWN);
        store.update(link(4, Identity.FRIEND), 1000);
        assertEquals(Identity.UNKNOWN, store.get("L-4").orElseThrow().identity());
        store.setIdentity("L-4", null);
        assertEquals(Identity.FRIEND, store.get("L-4").orElseThrow().identity(), "cleared -> reported");
        assertFalse(store.setIdentity("R-99", Identity.FRIEND));
        assertEquals(List.of("upd R-3 HOSTILE", "upd R-3 HOSTILE", "upd L-4 UNKNOWN", "upd L-4 UNKNOWN",
                "upd L-4 FRIEND"), rec.events);
    }

    @Test
    void dropsStaleTracksInDataTime() {
        TrackStore store = new TrackStore();
        Recorder rec = new Recorder();
        store.addListener(rec);
        store.apply(List.of(radar(1, 38), link(2, Identity.FRIEND)), List.of(), 0);
        store.update(link(2, Identity.FRIEND), 10_000);
        store.update(link(2, Identity.FRIEND), 15_000);
        assertTrue(store.get("R-1").isPresent());
        store.update(link(2, Identity.FRIEND), 16_000);
        assertTrue(store.get("R-1").isEmpty(), "R-1 not updated for 16 s");
        assertTrue(store.get("L-2").isPresent());
        assertTrue(rec.events.contains("drop R-1"));

        assertEquals(1, store.expire(40_000));
        assertEquals(0, store.size());
    }

    @Test
    void removeAndClear() {
        TrackStore store = new TrackStore();
        Recorder rec = new Recorder();
        store.apply(List.of(radar(1, 38), radar(2, 38)), List.of(), 5);
        store.setIdentity("R-2", Identity.FRIEND);
        store.addListener(rec);
        store.apply(List.of(), List.of("R-1", "R-77"), 6);
        assertEquals(List.of("drop R-1"), rec.events);
        assertTrue(store.remove("R-2"));
        assertFalse(store.remove("R-2"));
        store.update(radar(2, 38), 7);
        assertEquals(Identity.UNKNOWN, store.get("R-2").orElseThrow().identity(), "override dropped with track");
        store.clear();
        assertEquals(0, store.size());
        assertEquals(0, store.latestTimeMs());
        assertTrue(store.snapshot().isEmpty());
        assertEquals("drop R-2", rec.events.get(rec.events.size() - 1));
    }

    @Test
    void listenersRunOutsideLock() throws Exception {
        TrackStore store = new TrackStore();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        store.addListener((prev, cur) -> {
            // Another thread must be able to use the store while we are in the callback.
            Thread t = new Thread(() -> {
                try {
                    store.snapshot();
                    store.size();
                } catch (Throwable e) {
                    failure.set(e);
                } finally {
                    done.countDown();
                }
            });
            t.start();
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        store.update(radar(1, 38), 0);
        assertTrue(done.getCount() == 0, "store was locked during callback");
        assertNull(failure.get());
    }
}
