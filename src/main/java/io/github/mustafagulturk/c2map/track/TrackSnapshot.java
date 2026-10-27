package io.github.mustafagulturk.c2map.track;

import io.github.mustafagulturk.c2map.geo.GeoPoint;
import java.util.List;

/**
 * Immutable view of one track at a point in time, handed from the ingest side to the UI.
 *
 * @param id          track number, unique per source
 * @param source      where the track came from (RADAR = built from RDR1 plots, LINK = TRK1 message)
 * @param position    current estimated position
 * @param speedMps    ground speed in metres per second
 * @param courseDeg   course over ground, degrees clockwise from true north, [0, 360)
 * @param identity    friend / hostile / unknown
 * @param history     past positions, oldest first (the trail), not including {@code position}
 * @param updatedAtMs time of the last update, in the clock of the feeding source (live or replay)
 */
public record TrackSnapshot(
        int id,
        Source source,
        GeoPoint position,
        double speedMps,
        double courseDeg,
        Identity identity,
        List<GeoPoint> history,
        long updatedAtMs) {

    public enum Source { RADAR, LINK }

    public TrackSnapshot {
        history = List.copyOf(history);
    }

    /** Key that is unique across sources. */
    public String key() {
        return source.name().charAt(0) + "-" + id;
    }
}
