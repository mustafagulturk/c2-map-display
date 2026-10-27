package io.github.mustafagulturk.c2map.track;

import io.github.mustafagulturk.c2map.geo.GeoPoint;
import java.util.Objects;

/**
 * New kinematic state of one track, as handed to {@link TrackStore#apply}.
 *
 * @param source    RADAR or LINK
 * @param id        track number within the source
 * @param position  current position
 * @param speedMps  ground speed, m/s
 * @param courseDeg course, degrees clockwise from true north
 * @param identity  identity reported by the source (UNKNOWN for radar tracks)
 */
public record TrackUpdate(TrackSnapshot.Source source, int id, GeoPoint position, double speedMps,
        double courseDeg, Identity identity) {

    public TrackUpdate {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(identity, "identity");
    }

    public String key() {
        return TrackStore.key(source, id);
    }
}
