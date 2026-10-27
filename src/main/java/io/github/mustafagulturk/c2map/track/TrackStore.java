package io.github.mustafagulturk.c2map.track;

import io.github.mustafagulturk.c2map.geo.GeoPoint;
import io.github.mustafagulturk.c2map.geo.Geodesy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thread-safe picture of all tracks from all sources (RADAR from {@link PlotTracker}, LINK from
 * TRK1 messages).
 *
 * <p>Keeps a bounded trail per track (at most one point per trail interval), applies operator
 * identity overrides, and drops tracks that have not been updated for the timeout. All times are in
 * the clock of the feeding source ("data time"), so replay behaves like live data.
 *
 * <p>Listeners are called on the thread that made the change, after the change, outside the lock.
 */
public final class TrackStore {

    public static final long DEFAULT_TIMEOUT_MS = 15_000;
    public static final int DEFAULT_TRAIL_LENGTH = 60;
    public static final long DEFAULT_TRAIL_INTERVAL_MS = 2_000;
    private static final long EXPIRE_CHECK_INTERVAL_MS = 1_000;
    private static final Logger LOG = Logger.getLogger(TrackStore.class.getName());

    private static final class Entry {
        TrackSnapshot current;
        Identity reported;
        final ArrayDeque<GeoPoint> trail = new ArrayDeque<>();
        List<GeoPoint> trailView = List.of();
        long lastTrailMs;

        Entry(TrackSnapshot current, Identity reported) {
            this.current = current;
            this.reported = reported;
            this.lastTrailMs = current.updatedAtMs();
        }
    }

    /** A pending listener notification; {@code current == null} means dropped. */
    private record Event(TrackSnapshot previous, TrackSnapshot current) {
    }

    private final long timeoutMs;
    private final int trailLength;
    private final long trailIntervalMs;
    private final Map<String, Entry> tracks = new LinkedHashMap<>();
    private final Map<String, Identity> overrides = new HashMap<>();
    private final List<TrackListener> listeners = new CopyOnWriteArrayList<>();
    private long latestTimeMs;
    private long lastExpireCheckMs = Long.MIN_VALUE;
    private volatile List<TrackSnapshot> cachedSnapshot = List.of();

    public TrackStore() {
        this(DEFAULT_TIMEOUT_MS, DEFAULT_TRAIL_LENGTH, DEFAULT_TRAIL_INTERVAL_MS);
    }

    /**
     * @param timeoutMs       drop tracks not updated for this long (data time)
     * @param trailLength     maximum trail points per track
     * @param trailIntervalMs minimum data time between two trail points
     */
    public TrackStore(long timeoutMs, int trailLength, long trailIntervalMs) {
        if (timeoutMs <= 0 || trailLength < 0 || trailIntervalMs < 0) {
            throw new IllegalArgumentException("invalid track store parameters");
        }
        this.timeoutMs = timeoutMs;
        this.trailLength = trailLength;
        this.trailIntervalMs = trailIntervalMs;
    }

    /** Same as {@link TrackSnapshot#key()}. */
    public static String key(TrackSnapshot.Source source, int id) {
        return source.name().charAt(0) + "-" + id;
    }

    public void addListener(TrackListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(TrackListener listener) {
        listeners.remove(listener);
    }

    /** Applies one update at data time {@code timeMs}. */
    public void update(TrackUpdate update, long timeMs) {
        apply(List.of(update), List.of(), timeMs);
    }

    /**
     * Applies a batch of updates and removals at data time {@code timeMs}, then drops timed-out
     * tracks. Listeners see every change once the whole batch is applied.
     *
     * @param updates     new track states
     * @param removedKeys keys of tracks their source has deleted
     */
    public void apply(Collection<TrackUpdate> updates, Collection<String> removedKeys, long timeMs) {
        List<Event> events = new ArrayList<>(updates.size() + removedKeys.size());
        synchronized (this) {
            latestTimeMs = Math.max(latestTimeMs, timeMs);
            for (TrackUpdate u : updates) {
                events.add(applyLocked(u, timeMs));
            }
            for (String key : removedKeys) {
                Entry e = tracks.remove(key);
                if (e != null) {
                    overrides.remove(key);
                    events.add(new Event(e.current, null));
                }
            }
            if (lastExpireCheckMs == Long.MIN_VALUE || latestTimeMs - lastExpireCheckMs >= EXPIRE_CHECK_INTERVAL_MS
                    || latestTimeMs < lastExpireCheckMs) {
                lastExpireCheckMs = latestTimeMs;
                expireLocked(latestTimeMs, events);
            }
            if (!events.isEmpty()) {
                cachedSnapshot = null;
            }
        }
        fire(events);
    }

    private Event applyLocked(TrackUpdate u, long timeMs) {
        String key = u.key();
        Entry e = tracks.get(key);
        Identity identity = overrides.getOrDefault(key, u.identity());
        double course = Geodesy.normalizeDeg(u.courseDeg());
        if (e == null) {
            TrackSnapshot s = new TrackSnapshot(u.id(), u.source(), u.position(), u.speedMps(), course,
                    identity, List.of(), timeMs);
            tracks.put(key, new Entry(s, u.identity()));
            return new Event(null, s);
        }
        TrackSnapshot prev = e.current;
        e.reported = u.identity();
        if (trailLength > 0 && timeMs - e.lastTrailMs >= trailIntervalMs) {
            e.trail.addLast(prev.position());
            while (e.trail.size() > trailLength) {
                e.trail.removeFirst();
            }
            e.trailView = List.copyOf(e.trail);
            e.lastTrailMs = timeMs;
        }
        e.current = new TrackSnapshot(u.id(), u.source(), u.position(), u.speedMps(), course, identity,
                e.trailView, timeMs);
        return new Event(prev, e.current);
    }

    /** Removes one track (e.g. its source ended it). Returns false if it was not present. */
    public boolean remove(String key) {
        Event event;
        synchronized (this) {
            Entry e = tracks.remove(key);
            if (e == null) {
                return false;
            }
            overrides.remove(key);
            cachedSnapshot = null;
            event = new Event(e.current, null);
        }
        fire(List.of(event));
        return true;
    }

    /** Drops every track not updated within the timeout before {@code nowMs}; returns how many. */
    public int expire(long nowMs) {
        List<Event> events = new ArrayList<>();
        synchronized (this) {
            expireLocked(nowMs, events);
            if (!events.isEmpty()) {
                cachedSnapshot = null;
            }
        }
        fire(events);
        return events.size();
    }

    private void expireLocked(long nowMs, List<Event> events) {
        for (Iterator<Map.Entry<String, Entry>> it = tracks.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, Entry> me = it.next();
            if (nowMs - me.getValue().current.updatedAtMs() > timeoutMs) {
                overrides.remove(me.getKey());
                events.add(new Event(me.getValue().current, null));
                it.remove();
            }
        }
    }

    /**
     * Operator identity override. {@code null} clears it, so the track goes back to the identity
     * reported by its source (UNKNOWN for radar tracks). Takes effect immediately and notifies
     * listeners on the calling thread. Ignored for keys that are not in the store.
     *
     * @return true if the track exists
     */
    public boolean setIdentity(String key, Identity identity) {
        Event event;
        synchronized (this) {
            Entry e = tracks.get(key);
            if (e == null) {
                return false;
            }
            if (identity == null) {
                overrides.remove(key);
            } else {
                overrides.put(key, identity);
            }
            Identity effective = identity == null ? e.reported : identity;
            TrackSnapshot prev = e.current;
            if (prev.identity() == effective) {
                return true;
            }
            e.current = new TrackSnapshot(prev.id(), prev.source(), prev.position(), prev.speedMps(),
                    prev.courseDeg(), effective, prev.history(), prev.updatedAtMs());
            cachedSnapshot = null;
            event = new Event(prev, e.current);
        }
        fire(List.of(event));
        return true;
    }

    /** The operator override for {@code key}, if any. */
    public synchronized Optional<Identity> identityOverride(String key) {
        return Optional.ofNullable(overrides.get(key));
    }

    /** Immutable list of all current tracks; cheap to call every frame. */
    public List<TrackSnapshot> snapshot() {
        List<TrackSnapshot> s = cachedSnapshot;
        if (s != null) {
            return s;
        }
        synchronized (this) {
            if (cachedSnapshot == null) {
                List<TrackSnapshot> list = new ArrayList<>(tracks.size());
                for (Entry e : tracks.values()) {
                    list.add(e.current);
                }
                cachedSnapshot = List.copyOf(list);
            }
            return cachedSnapshot;
        }
    }

    public synchronized Optional<TrackSnapshot> get(String key) {
        Entry e = tracks.get(key);
        return e == null ? Optional.empty() : Optional.of(e.current);
    }

    public synchronized int size() {
        return tracks.size();
    }

    /** Newest data time seen so far, or 0 if none. */
    public synchronized long latestTimeMs() {
        return latestTimeMs;
    }

    /** Removes every track and override (listeners get onTrackDropped) and resets the data time. */
    public void clear() {
        List<Event> events = new ArrayList<>();
        synchronized (this) {
            for (Entry e : tracks.values()) {
                events.add(new Event(e.current, null));
            }
            tracks.clear();
            overrides.clear();
            latestTimeMs = 0;
            lastExpireCheckMs = Long.MIN_VALUE;
            cachedSnapshot = List.of();
        }
        fire(events);
    }

    private void fire(List<Event> events) {
        if (events.isEmpty() || listeners.isEmpty()) {
            return;
        }
        for (Event ev : events) {
            for (TrackListener l : listeners) {
                try {
                    if (ev.current() == null) {
                        l.onTrackDropped(ev.previous());
                    } else {
                        l.onTrackUpdated(ev.previous(), ev.current());
                    }
                } catch (RuntimeException ex) {
                    LOG.log(Level.WARNING, "track listener failed", ex);
                }
            }
        }
    }
}
