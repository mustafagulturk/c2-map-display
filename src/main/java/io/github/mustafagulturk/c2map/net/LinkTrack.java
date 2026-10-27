package io.github.mustafagulturk.c2map.net;

import io.github.mustafagulturk.c2map.track.Identity;
import java.util.Objects;

/**
 * One track entry of a TRK1 message.
 *
 * @param id        track number, 0 .. 2^31-1
 * @param identity  reported standard identity
 * @param flags     bit field, see {@link #FLAG_ENDED}
 * @param lat       latitude, degrees
 * @param lon       longitude, degrees
 * @param speedMps  ground speed, m/s
 * @param courseDeg course over ground, degrees clockwise from true north
 */
public record LinkTrack(int id, Identity identity, int flags, double lat, double lon, double speedMps,
        double courseDeg) {

    /** The sender has stopped reporting this track; receivers drop it immediately. */
    public static final int FLAG_ENDED = 0x01;

    public LinkTrack {
        Objects.requireNonNull(identity, "identity");
        if (id < 0) {
            throw new IllegalArgumentException("negative track id " + id);
        }
    }

    public boolean ended() {
        return (flags & FLAG_ENDED) != 0;
    }
}
