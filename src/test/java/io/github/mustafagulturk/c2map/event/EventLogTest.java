package io.github.mustafagulturk.c2map.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventLogTest {

    @Test
    void boundedDropsOldest() {
        EventLog log = new EventLog(3);
        for (int i = 0; i < 5; i++) {
            log.log(Severity.INFO, "e" + i, i);
        }
        assertEquals(List.of("e2", "e3", "e4"), log.snapshot().stream().map(Event::message).toList());
        assertEquals(3, log.size());
        assertEquals(5, log.totalCount());
        log.clear();
        assertEquals(0, log.size());
        assertEquals(5, log.totalCount());
    }

    @Test
    void listenersAndAlarmSinks() {
        EventLog log = new EventLog();
        List<Event> all = new ArrayList<>();
        List<Event> alarms = new ArrayList<>();
        log.addListener(e -> {
            throw new IllegalStateException("bad listener");
        });
        log.addListener(all::add);
        log.addAlarmSink(alarms::add);
        log.add(Event.info(1, "bilgi"));
        log.add(Event.warning(2, "uyarı"));
        log.add(Event.alarm(3, "alarm"));
        assertEquals(3, all.size());
        assertEquals(1, alarms.size());
        assertEquals("alarm", alarms.get(0).message());
        assertEquals(1, log.unacknowledgedAlarms());
        log.acknowledgeAlarms();
        assertEquals(0, log.unacknowledgedAlarms());

        EventLog.Listener l = all::add;
        log.addListener(l);
        log.removeListener(l);
        log.add(Event.info(4, "x"));
        assertEquals(4, all.size());
    }

    @Test
    void snapshotIsImmutableCopy() {
        EventLog log = new EventLog();
        log.add(Event.info(1, "a"));
        List<Event> snap = log.snapshot();
        log.add(Event.info(2, "b"));
        assertEquals(1, snap.size());
        assertThrows(UnsupportedOperationException.class, () -> snap.add(Event.info(0, "c")));
    }

    @Test
    void concurrentAdds() throws InterruptedException {
        EventLog log = new EventLog(100);
        List<Thread> ts = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            Thread th = new Thread(() -> {
                for (int i = 0; i < 1000; i++) {
                    log.add(Event.alarm(i, "a"));
                }
            });
            ts.add(th);
            th.start();
        }
        for (Thread th : ts) {
            th.join();
        }
        assertEquals(8000, log.totalCount());
        assertEquals(100, log.size());
        assertEquals(8000, log.unacknowledgedAlarms());
    }

    @Test
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> new EventLog(0));
        assertThrows(NullPointerException.class, () -> new Event(0, null, "m", null, null));
        assertEquals("Uyarı", Severity.WARNING.label());
    }
}
