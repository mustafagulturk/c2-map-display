package io.github.mustafagulturk.c2map.zone;

import static io.github.mustafagulturk.c2map.zone.RestrictedZoneTest.ANKARA;
import static io.github.mustafagulturk.c2map.zone.RestrictedZoneTest.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mustafagulturk.c2map.event.Event;
import io.github.mustafagulturk.c2map.event.EventLog;
import io.github.mustafagulturk.c2map.event.Severity;
import io.github.mustafagulturk.c2map.geo.GeoPoint;
import io.github.mustafagulturk.c2map.geo.Geodesy;
import io.github.mustafagulturk.c2map.track.Identity;
import io.github.mustafagulturk.c2map.track.TrackSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ZoneMonitorTest {

    private final List<Event> events = Collections.synchronizedList(new ArrayList<>());
    private ZoneMonitor monitor;
    private RestrictedZone zone1;

    @BeforeEach
    void setUp() {
        monitor = new ZoneMonitor(events::add, () -> 42L);
        zone1 = monitor.createZone(square(0, 0, 1000));
        events.clear();
    }

    static List<GeoPoint> square(double e, double n, double size) {
        return List.of(at(ANKARA, e, n), at(ANKARA, e + size, n), at(ANKARA, e + size, n + size), at(ANKARA, e, n + size));
    }

    static TrackSnapshot track(int id, Identity identity, GeoPoint pos, long t) {
        return new TrackSnapshot(id, TrackSnapshot.Source.RADAR, pos, 100, 90, identity, List.of(), t);
    }

    /** Feeds a sequence of positions for one track, returning the last snapshot. */
    private TrackSnapshot feed(TrackSnapshot prev, int id, Identity identity, GeoPoint pos, long t) {
        TrackSnapshot cur = track(id, identity, pos, t);
        monitor.onTrackUpdated(prev, cur);
        return cur;
    }

    @Test
    void enterAndLeave() {
        TrackSnapshot t = feed(null, 12, Identity.UNKNOWN, at(ANKARA, -500, 500), 1000);
        assertTrue(events.isEmpty());
        t = feed(t, 12, Identity.UNKNOWN, at(ANKARA, 200, 500), 3000);
        assertEquals(1, events.size());
        Event e = events.get(0);
        assertEquals(Severity.ALARM, e.severity());
        assertEquals("R-12 (Bilinmeyen) yasak bölgeye girdi: Bölge-1", e.message());
        assertEquals("R-12", e.trackKey());
        assertEquals(zone1.id(), e.zoneId());
        assertEquals(3000, e.timeMs());
        assertEquals(Set.of(zone1.id()), monitor.intrudedZoneIds());
        assertEquals(List.of("R-12"), monitor.intruders(zone1.id()));

        // Staying inside: no repeat.
        t = feed(t, 12, Identity.UNKNOWN, at(ANKARA, 400, 500), 5000);
        assertEquals(1, events.size());

        feed(t, 12, Identity.UNKNOWN, at(ANKARA, 1200, 500), 7000);
        assertEquals(2, events.size());
        Event out = events.get(1);
        assertEquals(Severity.INFO, out.severity());
        assertEquals("R-12 (Bilinmeyen) yasak bölgeden çıktı: Bölge-1", out.message());
        assertEquals(7000, out.timeMs());
        assertTrue(monitor.intrudedZoneIds().isEmpty());
    }

    @Test
    void reentryAlarmsAgain() {
        TrackSnapshot t = feed(null, 1, Identity.HOSTILE, at(ANKARA, -100, 500), 0);
        t = feed(t, 1, Identity.HOSTILE, at(ANKARA, 100, 500), 1);
        t = feed(t, 1, Identity.HOSTILE, at(ANKARA, -100, 500), 2);
        feed(t, 1, Identity.HOSTILE, at(ANKARA, 100, 500), 3);
        assertEquals(List.of(Severity.ALARM, Severity.INFO, Severity.ALARM),
                events.stream().map(Event::severity).toList());
        assertTrue(events.get(0).message().startsWith("R-1 (Düşman)"));
    }

    @Test
    void firstSeenInsideAlarms() {
        feed(null, 7, Identity.HOSTILE, at(ANKARA, 500, 500), 10);
        assertEquals(1, events.size());
        assertEquals(Severity.ALARM, events.get(0).severity());
        assertEquals("R-7 (Düşman) yasak bölge içinde tespit edildi: Bölge-1", events.get(0).message());
    }

    @Test
    void friendGetsWarning() {
        TrackSnapshot t = feed(null, 3, Identity.FRIEND, at(ANKARA, -100, 100), 0);
        feed(t, 3, Identity.FRIEND, at(ANKARA, 100, 100), 1);
        assertEquals(1, events.size());
        assertEquals(Severity.WARNING, events.get(0).severity());
        assertEquals("R-3 (Dost) yasak bölgeye girdi: Bölge-1", events.get(0).message());
    }

    @Test
    void linkTrackKeyIsDistinctFromRadarTrack() {
        monitor.onTrackUpdated(null, track(5, Identity.UNKNOWN, at(ANKARA, 500, 500), 0));
        TrackSnapshot link = new TrackSnapshot(5, TrackSnapshot.Source.LINK, at(ANKARA, 500, 500), 0, 0,
                Identity.HOSTILE, List.of(), 0);
        monitor.onTrackUpdated(null, link);
        assertEquals(2, events.size());
        assertEquals("L-5", events.get(1).trackKey());
        assertEquals(List.of("L-5", "R-5"), monitor.intruders(zone1.id()));
    }

    @Test
    void newZoneAlarmsForTracksAlreadyInsideOnNextUpdate() {
        TrackSnapshot t = feed(null, 9, Identity.UNKNOWN, at(ANKARA, 5500, 500), 0);
        assertTrue(events.isEmpty());
        RestrictedZone zone2 = monitor.createZone(square(5000, 0, 1000));
        assertEquals("Bölge-2", zone2.name());
        assertEquals(1, events.size());
        assertEquals("Yasak bölge eklendi: Bölge-2", events.get(0).message());
        assertEquals(42, events.get(0).timeMs());
        feed(t, 9, Identity.UNKNOWN, at(ANKARA, 5510, 500), 100);
        assertEquals(2, events.size());
        assertEquals(Severity.ALARM, events.get(1).severity());
        assertEquals("R-9 (Bilinmeyen) yasak bölge içinde tespit edildi: Bölge-2", events.get(1).message());
        assertEquals(zone2.id(), events.get(1).zoneId());
    }

    @Test
    void droppedTrackClearsState() {
        TrackSnapshot t = feed(null, 4, Identity.UNKNOWN, at(ANKARA, 500, 500), 0);
        monitor.onTrackDropped(t);
        assertTrue(monitor.intrudedZoneIds().isEmpty());
        assertEquals(1, events.size());
        // Same track number re-appears inside: alarms again.
        feed(null, 4, Identity.UNKNOWN, at(ANKARA, 500, 500), 10);
        assertEquals(2, events.size());
        assertEquals(Severity.ALARM, events.get(1).severity());
    }

    @Test
    void removedZoneClearsStateWithoutExitEvent() {
        TrackSnapshot t = feed(null, 4, Identity.UNKNOWN, at(ANKARA, 500, 500), 0);
        events.clear();
        assertTrue(monitor.removeZone(zone1.id()));
        assertFalse(monitor.removeZone(zone1.id()));
        assertEquals(1, events.size());
        assertEquals("Yasak bölge silindi: Bölge-1", events.get(0).message());
        assertTrue(monitor.intrudedZoneIds().isEmpty());
        feed(t, 4, Identity.UNKNOWN, at(ANKARA, 600, 500), 1);
        assertEquals(1, events.size());
    }

    @Test
    void overlappingZonesEachAlarm() {
        RestrictedZone zone2 = monitor.createZone(square(500, 0, 1000));
        events.clear();
        TrackSnapshot t = feed(null, 1, Identity.UNKNOWN, at(ANKARA, 250, -100), 0);
        t = feed(t, 1, Identity.UNKNOWN, at(ANKARA, 750, 100), 1);
        assertEquals(2, events.size());
        assertEquals(List.of(zone1.id(), zone2.id()), events.stream().map(Event::zoneId).toList());
        // Move from the overlap into zone2 only: leaves zone1 only.
        feed(t, 1, Identity.UNKNOWN, at(ANKARA, 1250, 100), 2);
        assertEquals(3, events.size());
        assertEquals(Severity.INFO, events.get(2).severity());
        assertEquals(zone1.id(), events.get(2).zoneId());
        assertEquals(Set.of(zone2.id()), monitor.intrudedZoneIds());
    }

    @Test
    void zoneManagement() {
        List<List<RestrictedZone>> notifications = new ArrayList<>();
        monitor.addZoneListener(notifications::add);
        RestrictedZone z2 = monitor.createZone(square(5000, 0, 100));
        assertEquals(2, notifications.get(0).size());
        assertThrows(IllegalArgumentException.class, () -> monitor.addZone(z2));
        assertEquals("Bölge-3", monitor.nextZoneName());
        monitor.removeZone(zone1.id());
        // Highest number still in use is 2.
        assertEquals("Bölge-3", monitor.nextZoneName());
        assertEquals(z2, monitor.zone(z2.id()).orElseThrow());
        assertEquals(z2, monitor.zoneAt(at(ANKARA, 5050, 50)).orElseThrow());
        assertTrue(monitor.zoneAt(at(ANKARA, 500, 500)).isEmpty());

        events.clear();
        monitor.clear();
        assertTrue(monitor.zones().isEmpty());
        assertEquals("Tüm yasak bölgeler silindi", events.get(0).message());
        assertNull(events.get(0).trackKey());
        monitor.clear();
        assertEquals(1, events.size());
        assertEquals("Bölge-1", monitor.nextZoneName());
    }

    @Test
    void setZonesIsSilentAndResetsState() {
        feed(null, 1, Identity.UNKNOWN, at(ANKARA, 500, 500), 0);
        events.clear();
        RestrictedZone a = new RestrictedZone("a", "Bölge-7", square(0, 0, 1000), 1);
        monitor.setZones(List.of(a));
        assertTrue(events.isEmpty());
        assertEquals(List.of(a), monitor.zones());
        assertEquals("Bölge-8", monitor.nextZoneName());
        assertThrows(IllegalArgumentException.class, () -> monitor.setZones(List.of(a, a)));
    }

    @Test
    void topmostZoneWins() {
        RestrictedZone z2 = monitor.createZone(square(0, 0, 1000));
        assertEquals(z2, monitor.zoneAt(at(ANKARA, 500, 500)).orElseThrow());
    }

    @Test
    void eventLogIntegration() {
        EventLog log = new EventLog();
        AtomicReference<Event> alarm = new AtomicReference<>();
        log.addAlarmSink(alarm::set);
        ZoneMonitor m = new ZoneMonitor(log);
        RestrictedZone z = m.createZone(square(0, 0, 1000));
        m.onTrackUpdated(null, track(1, Identity.UNKNOWN, at(ANKARA, 500, 500), 99));
        assertEquals(2, log.size());
        assertEquals(z.id(), alarm.get().zoneId());
    }

    @Test
    void concurrentUpdatesAndEdits() throws Exception {
        int threads = 4;
        int steps = 2000;
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int w = 0; w < threads; w++) {
            int id = w;
            Thread th = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                TrackSnapshot prev = null;
                for (int i = 0; i < steps; i++) {
                    // Oscillate in and out of zone1 every step.
                    double east = (i % 2 == 0) ? -100 : 100;
                    TrackSnapshot cur = track(id, Identity.UNKNOWN, Geodesy.fromEastNorth(ANKARA, east, 500), i);
                    monitor.onTrackUpdated(prev, cur);
                    prev = cur;
                }
            });
            workers.add(th);
            th.start();
        }
        Thread editor = new Thread(() -> {
            for (int i = 0; i < 200; i++) {
                RestrictedZone z = monitor.createZone(square(10_000, 0, 100));
                monitor.removeZone(z.id());
            }
        });
        go.countDown();
        editor.start();
        for (Thread th : workers) {
            th.join();
        }
        editor.join();
        long alarms = events.stream().filter(e -> e.severity() == Severity.ALARM).count();
        long exits = events.stream().filter(e -> e.message().contains("çıktı")).count();
        assertEquals((long) threads * steps / 2, alarms);
        assertEquals((long) threads * (steps / 2 - 1), exits);
        assertEquals(List.of(zone1), monitor.zones());
    }
}
