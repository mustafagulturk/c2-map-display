package io.github.mustafagulturk.c2map.event;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe, bounded operator event log. When full, the oldest entry is dropped.
 *
 * <p>Listeners and alarm sinks are called synchronously on the thread that added the event (often
 * the ingest thread), outside any lock. This class knows nothing about JavaFX: UI code must
 * marshal to the FX thread itself (e.g. {@code Platform.runLater}). An exception thrown by one
 * listener is reported and does not affect the others or the caller.
 */
public final class EventLog {

    public static final int DEFAULT_CAPACITY = 1000;

    /** Notified of every new event. */
    @FunctionalInterface
    public interface Listener {
        void onEvent(Event event);
    }

    /** Notified only of {@link Severity#ALARM} events, e.g. to flash the screen and beep. */
    @FunctionalInterface
    public interface AlarmSink {
        void onAlarm(Event event);
    }

    private static final System.Logger LOG = System.getLogger(EventLog.class.getName());

    private final int capacity;
    private final Object lock = new Object();
    private final ArrayDeque<Event> entries;
    private long totalCount;
    private int unacknowledgedAlarms;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<AlarmSink> alarmSinks = new CopyOnWriteArrayList<>();

    public EventLog() {
        this(DEFAULT_CAPACITY);
    }

    public EventLog(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        this.entries = new ArrayDeque<>(Math.min(capacity, 1024));
    }

    /** Appends an event and notifies listeners (and alarm sinks for alarms). */
    public void add(Event event) {
        Objects.requireNonNull(event, "event");
        synchronized (lock) {
            if (entries.size() == capacity) {
                entries.removeFirst();
            }
            entries.addLast(event);
            totalCount++;
            if (event.severity() == Severity.ALARM) {
                unacknowledgedAlarms++;
            }
        }
        for (Listener l : listeners) {
            try {
                l.onEvent(event);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "event listener failed", e);
            }
        }
        if (event.severity() == Severity.ALARM) {
            for (AlarmSink s : alarmSinks) {
                try {
                    s.onAlarm(event);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "alarm sink failed", e);
                }
            }
        }
    }

    /** Convenience for events not tied to a track or zone. */
    public void log(Severity severity, String message, long timeMs) {
        add(new Event(timeMs, severity, message, null, null));
    }

    /** Current entries, oldest first. */
    public List<Event> snapshot() {
        synchronized (lock) {
            return List.copyOf(entries);
        }
    }

    public int size() {
        synchronized (lock) {
            return entries.size();
        }
    }

    public int capacity() {
        return capacity;
    }

    /** Number of events ever added, including those dropped because the log was full. */
    public long totalCount() {
        synchronized (lock) {
            return totalCount;
        }
    }

    /** Alarms added since the last {@link #acknowledgeAlarms()}; the UI can flash while this is non-zero. */
    public int unacknowledgedAlarms() {
        synchronized (lock) {
            return unacknowledgedAlarms;
        }
    }

    /** Operator acknowledged all pending alarms. */
    public void acknowledgeAlarms() {
        synchronized (lock) {
            unacknowledgedAlarms = 0;
        }
    }

    /** Removes all entries (does not reset {@link #totalCount()}). */
    public void clear() {
        synchronized (lock) {
            entries.clear();
            unacknowledgedAlarms = 0;
        }
    }

    public void addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void addAlarmSink(AlarmSink sink) {
        alarmSinks.add(Objects.requireNonNull(sink));
    }

    public void removeAlarmSink(AlarmSink sink) {
        alarmSinks.remove(sink);
    }
}
