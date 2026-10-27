package io.github.mustafagulturk.c2map.zone;

import io.github.mustafagulturk.c2map.event.Event;
import io.github.mustafagulturk.c2map.event.EventLog;
import io.github.mustafagulturk.c2map.event.Severity;
import io.github.mustafagulturk.c2map.geo.GeoPoint;
import io.github.mustafagulturk.c2map.track.Identity;
import io.github.mustafagulturk.c2map.track.TrackSnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owns the restricted zones and raises events when tracks enter or leave them.
 *
 * <p>Feed it with {@link #onTrackUpdated} / {@link #onTrackDropped}; their signatures match the
 * track store's listener, so registration is a one-line adapter. Rules:
 * <ul>
 *   <li>outside &rarr; inside: an {@link Severity#ALARM} event (a {@link Severity#WARNING} for
 *       friendly tracks);</li>
 *   <li>a track first seen inside a zone, or inside a newly added zone, alarms on its next update;</li>
 *   <li>inside &rarr; outside: an {@link Severity#INFO} event;</li>
 *   <li>a dropped track or a removed zone just clears the state, without an event.</li>
 * </ul>
 * Track events carry the track's data time ({@code updatedAtMs}); zone edits use the clock given
 * to the constructor. All methods are thread-safe; events and zone listeners are delivered on the
 * calling thread, outside the internal lock.
 */
public final class ZoneMonitor {

    /** Notified with the full zone list after every change. */
    @FunctionalInterface
    public interface ZoneListener {
        void onZonesChanged(List<RestrictedZone> zones);
    }

    /** Prefix of generated zone names ("Bölge-1", "Bölge-2", ...). */
    public static final String NAME_PREFIX = "Bölge-";

    private static final Pattern GENERATED_NAME = Pattern.compile(Pattern.quote(NAME_PREFIX) + "(\\d{1,9})");

    private final Consumer<Event> events;
    private final LongSupplier clock;
    private final Object lock = new Object();
    /** Guarded by lock. Insertion order = paint order. */
    private final List<RestrictedZone> zones = new ArrayList<>();
    /** Guarded by lock. Track key -> ids of the zones the track is currently inside. */
    private final Map<String, Set<String>> inside = new HashMap<>();
    private final List<ZoneListener> listeners = new CopyOnWriteArrayList<>();

    /** Events go to {@code log}; zone edits are stamped with the wall clock. */
    public ZoneMonitor(EventLog log) {
        this(log::add, System::currentTimeMillis);
    }

    public ZoneMonitor(Consumer<Event> events, LongSupplier clock) {
        this.events = Objects.requireNonNull(events);
        this.clock = Objects.requireNonNull(clock);
    }

    // ---------------------------------------------------------------- zones

    /** Current zones in insertion order. */
    public List<RestrictedZone> zones() {
        synchronized (lock) {
            return List.copyOf(zones);
        }
    }

    public Optional<RestrictedZone> zone(String id) {
        synchronized (lock) {
            return zones.stream().filter(z -> z.id().equals(id)).findFirst();
        }
    }

    /** The most recently added zone containing {@code p} (the one drawn on top), if any. */
    public Optional<RestrictedZone> zoneAt(GeoPoint p) {
        List<RestrictedZone> copy = zones();
        for (int i = copy.size() - 1; i >= 0; i--) {
            if (copy.get(i).contains(p)) {
                return Optional.of(copy.get(i));
            }
        }
        return Optional.empty();
    }

    /** Name the next {@link #createZone} call would use. */
    public String nextZoneName() {
        synchronized (lock) {
            return nextNameLocked();
        }
    }

    /** Creates a zone with a fresh id and the next free "Bölge-N" name, adds it and returns it. */
    public RestrictedZone createZone(List<GeoPoint> vertices) {
        RestrictedZone zone;
        List<RestrictedZone> snapshot;
        synchronized (lock) {
            zone = new RestrictedZone(UUID.randomUUID().toString(), nextNameLocked(), vertices, clock.getAsLong());
            zones.add(zone);
            snapshot = List.copyOf(zones);
        }
        afterChange(snapshot, Event.info(zone.createdAtMs(), "Yasak bölge eklendi: " + zone.name()), zone.id());
        return zone;
    }

    /**
     * Adds a zone. Tracks already inside it will alarm on their next update.
     *
     * @throws IllegalArgumentException if a zone with the same id exists
     */
    public void addZone(RestrictedZone zone) {
        Objects.requireNonNull(zone);
        List<RestrictedZone> snapshot;
        synchronized (lock) {
            if (zones.stream().anyMatch(z -> z.id().equals(zone.id()))) {
                throw new IllegalArgumentException("duplicate zone id " + zone.id());
            }
            zones.add(zone);
            snapshot = List.copyOf(zones);
        }
        afterChange(snapshot, Event.info(clock.getAsLong(), "Yasak bölge eklendi: " + zone.name()), zone.id());
    }

    /** Removes a zone; returns false if no zone has that id. */
    public boolean removeZone(String id) {
        RestrictedZone removed = null;
        List<RestrictedZone> snapshot;
        synchronized (lock) {
            for (int i = 0; i < zones.size(); i++) {
                if (zones.get(i).id().equals(id)) {
                    removed = zones.remove(i);
                    break;
                }
            }
            if (removed == null) {
                return false;
            }
            for (Set<String> ids : inside.values()) {
                ids.remove(id);
            }
            inside.values().removeIf(Set::isEmpty);
            snapshot = List.copyOf(zones);
        }
        afterChange(snapshot, Event.info(clock.getAsLong(), "Yasak bölge silindi: " + removed.name()), id);
        return true;
    }

    /** Removes all zones. */
    public void clear() {
        List<RestrictedZone> snapshot;
        synchronized (lock) {
            if (zones.isEmpty()) {
                return;
            }
            zones.clear();
            inside.clear();
            snapshot = List.of();
        }
        afterChange(snapshot, Event.info(clock.getAsLong(), "Tüm yasak bölgeler silindi"), null);
    }

    /** Replaces all zones without logging events (e.g. when loading saved zones at start-up). */
    public void setZones(List<RestrictedZone> newZones) {
        List<RestrictedZone> snapshot = List.copyOf(newZones);
        if (snapshot.stream().map(RestrictedZone::id).distinct().count() != snapshot.size()) {
            throw new IllegalArgumentException("duplicate zone ids");
        }
        synchronized (lock) {
            zones.clear();
            zones.addAll(snapshot);
            inside.clear();
        }
        fireZonesChanged(snapshot);
    }

    public void addZoneListener(ZoneListener l) {
        listeners.add(Objects.requireNonNull(l));
    }

    public void removeZoneListener(ZoneListener l) {
        listeners.remove(l);
    }

    // --------------------------------------------------------------- tracks

    /** Same signature as the track store listener. {@code previousOrNull} is null for a new track. */
    public void onTrackUpdated(TrackSnapshot previousOrNull, TrackSnapshot current) {
        Objects.requireNonNull(current);
        List<Event> out = new ArrayList<>(2);
        synchronized (lock) {
            Set<String> was = inside.getOrDefault(current.key(), Set.of());
            Set<String> now = new HashSet<>();
            for (RestrictedZone zone : zones) {
                boolean in = zone.contains(current.position());
                if (in) {
                    now.add(zone.id());
                }
                boolean wasIn = was.contains(zone.id());
                if (in && !wasIn) {
                    boolean alreadyInside = previousOrNull == null || zone.contains(previousOrNull.position());
                    out.add(entered(current, zone, alreadyInside));
                } else if (!in && wasIn) {
                    out.add(left(current, zone));
                }
            }
            if (now.isEmpty()) {
                inside.remove(current.key());
            } else {
                inside.put(current.key(), now);
            }
        }
        out.forEach(events);
    }

    /** Same signature as the track store listener: forgets the track's zone state. */
    public void onTrackDropped(TrackSnapshot track) {
        synchronized (lock) {
            inside.remove(track.key());
        }
    }

    /** Ids of the zones that currently contain at least one track. */
    public Set<String> intrudedZoneIds() {
        synchronized (lock) {
            Set<String> ids = new HashSet<>();
            inside.values().forEach(ids::addAll);
            return ids;
        }
    }

    /** Keys of the tracks currently inside the zone, sorted. */
    public List<String> intruders(String zoneId) {
        synchronized (lock) {
            Set<String> keys = new TreeSet<>();
            inside.forEach((key, ids) -> {
                if (ids.contains(zoneId)) {
                    keys.add(key);
                }
            });
            return List.copyOf(keys);
        }
    }

    /** Turkish operator name of an identity. */
    public static String identityText(Identity identity) {
        return switch (identity) {
            case FRIEND -> "Dost";
            case HOSTILE -> "Düşman";
            case UNKNOWN -> "Bilinmeyen";
        };
    }

    // -------------------------------------------------------------- helpers

    private static Event entered(TrackSnapshot t, RestrictedZone zone, boolean alreadyInside) {
        Severity severity = t.identity() == Identity.FRIEND ? Severity.WARNING : Severity.ALARM;
        String verb = alreadyInside ? "yasak bölge içinde tespit edildi: " : "yasak bölgeye girdi: ";
        return new Event(t.updatedAtMs(), severity, trackText(t) + " " + verb + zone.name(), t.key(), zone.id());
    }

    private static Event left(TrackSnapshot t, RestrictedZone zone) {
        return new Event(t.updatedAtMs(), Severity.INFO,
                trackText(t) + " yasak bölgeden çıktı: " + zone.name(), t.key(), zone.id());
    }

    private static String trackText(TrackSnapshot t) {
        return t.key() + " (" + identityText(t.identity()) + ")";
    }

    private String nextNameLocked() {
        int max = 0;
        for (RestrictedZone z : zones) {
            Matcher m = GENERATED_NAME.matcher(z.name());
            if (m.matches()) {
                max = Math.max(max, Integer.parseInt(m.group(1)));
            }
        }
        return NAME_PREFIX + (max + 1);
    }

    private void afterChange(List<RestrictedZone> snapshot, Event event, String zoneId) {
        events.accept(new Event(event.timeMs(), event.severity(), event.message(), null, zoneId));
        fireZonesChanged(snapshot);
    }

    private void fireZonesChanged(List<RestrictedZone> snapshot) {
        for (ZoneListener l : listeners) {
            l.onZonesChanged(snapshot);
        }
    }
}
