package io.github.mustafagulturk.c2map.event;

import java.util.Objects;

/**
 * One entry of the operator event log.
 *
 * @param timeMs    when it happened, in the clock of the active data source (live or replay)
 * @param severity  info / warning / alarm
 * @param message   operator-facing text (Turkish)
 * @param trackKey  related track ({@code TrackSnapshot.key()}), or {@code null}
 * @param zoneId    related restricted zone id, or {@code null}
 */
public record Event(long timeMs, Severity severity, String message, String trackKey, String zoneId) {

    public Event {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
    }

    public static Event info(long timeMs, String message) {
        return new Event(timeMs, Severity.INFO, message, null, null);
    }

    public static Event warning(long timeMs, String message) {
        return new Event(timeMs, Severity.WARNING, message, null, null);
    }

    public static Event alarm(long timeMs, String message) {
        return new Event(timeMs, Severity.ALARM, message, null, null);
    }
}
