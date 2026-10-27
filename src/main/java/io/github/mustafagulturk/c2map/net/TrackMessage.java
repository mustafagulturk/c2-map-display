package io.github.mustafagulturk.c2map.net;

import java.util.List;

/**
 * Decoded TRK1 datagram.
 *
 * @param sequence    unsigned 32-bit datagram counter of the sender
 * @param timestampMs time of validity of all tracks in the message, epoch milliseconds
 * @param tracks      track entries
 */
public record TrackMessage(long sequence, long timestampMs, List<LinkTrack> tracks) {

    public TrackMessage {
        tracks = List.copyOf(tracks);
    }
}
