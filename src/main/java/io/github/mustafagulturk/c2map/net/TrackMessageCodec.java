package io.github.mustafagulturk.c2map.net;

import io.github.mustafagulturk.c2map.track.Identity;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Encoder/decoder for the TRK1 data-link track message. All fields are big-endian:
 *
 * <pre>
 * offset size  field
 * 0      4     magic         'T','R','K','1' (0x54524B31)
 * 4      1     version       1
 * 5      1     reserved      0
 * 6      2     track_count   N (u16)
 * 8      4     sequence      u32, increments per datagram
 * 12     8     timestamp_ms  u64, epoch ms; time of validity of every track in the datagram
 * 20     30*N  tracks:
 *              +0  4  id         u32, must be &lt; 2^31
 *              +4  1  identity   u8, {@link Identity#code()} (unknown codes read as UNKNOWN)
 *              +5  1  flags      u8, bit 0 = track ended ({@link LinkTrack#FLAG_ENDED})
 *              +6  8  lat        f64, degrees [-90, 90]
 *              +14 8  lon        f64, degrees
 *              +22 4  speed_mps  f32, &gt;= 0
 *              +26 4  course_deg f32, degrees clockwise from true north
 * </pre>
 *
 * A datagram holds at most {@link #MAX_TRACKS_PER_DATAGRAM} (46) tracks so it stays within
 * {@link #MAX_DATAGRAM_SIZE} bytes; {@link #encodeSplit} spreads a large picture over several
 * datagrams. Malformed input (wrong magic or version, truncated payload, trailing bytes,
 * non-finite or out-of-range values) decodes to {@code Optional.empty()}.
 */
public final class TrackMessageCodec {

    public static final int MAGIC = 0x54524B31;
    public static final int VERSION = 1;
    public static final int HEADER_SIZE = 20;
    public static final int TRACK_SIZE = 30;
    public static final int MAX_DATAGRAM_SIZE = 1400;
    public static final int MAX_TRACKS_PER_DATAGRAM = (MAX_DATAGRAM_SIZE - HEADER_SIZE) / TRACK_SIZE;

    private TrackMessageCodec() {
    }

    /**
     * Serialises one message.
     *
     * @throws IllegalArgumentException if it has more than {@link #MAX_TRACKS_PER_DATAGRAM} tracks
     */
    public static byte[] encode(TrackMessage message) {
        int n = message.tracks().size();
        if (n > MAX_TRACKS_PER_DATAGRAM) {
            throw new IllegalArgumentException("too many tracks for one datagram: " + n);
        }
        ByteBuffer b = ByteBuffer.allocate(HEADER_SIZE + n * TRACK_SIZE);
        b.putInt(MAGIC);
        b.put((byte) VERSION);
        b.put((byte) 0);
        b.putShort((short) n);
        b.putInt((int) message.sequence());
        b.putLong(message.timestampMs());
        for (LinkTrack t : message.tracks()) {
            b.putInt(t.id());
            b.put(t.identity().code());
            b.put((byte) t.flags());
            b.putDouble(t.lat());
            b.putDouble(t.lon());
            b.putFloat((float) t.speedMps());
            b.putFloat((float) t.courseDeg());
        }
        return b.array();
    }

    /**
     * Encodes any number of tracks into datagrams of at most {@link #MAX_TRACKS_PER_DATAGRAM}
     * tracks. Datagram {@code i} gets sequence {@code firstSequence + i}. An empty list gives one
     * empty datagram (a heartbeat).
     */
    public static List<byte[]> encodeSplit(long firstSequence, long timestampMs, List<LinkTrack> tracks) {
        List<byte[]> out = new ArrayList<>(tracks.size() / MAX_TRACKS_PER_DATAGRAM + 1);
        int offset = 0;
        long seq = firstSequence;
        do {
            int n = Math.min(MAX_TRACKS_PER_DATAGRAM, tracks.size() - offset);
            out.add(encode(new TrackMessage(seq++ & 0xFFFF_FFFFL, timestampMs,
                    tracks.subList(offset, offset + n))));
            offset += n;
        } while (offset < tracks.size());
        return out;
    }

    public static Optional<TrackMessage> decode(byte[] data) {
        return data == null ? Optional.empty() : decode(data, 0, data.length);
    }

    /** Parses {@code length} bytes at {@code offset}; empty for anything malformed. */
    public static Optional<TrackMessage> decode(byte[] data, int offset, int length) {
        if (data == null || length < HEADER_SIZE) {
            return Optional.empty();
        }
        ByteBuffer b = ByteBuffer.wrap(data, offset, length);
        if (b.getInt() != MAGIC || Byte.toUnsignedInt(b.get()) != VERSION) {
            return Optional.empty();
        }
        b.get(); // reserved
        int count = Short.toUnsignedInt(b.getShort());
        if (b.remaining() != (HEADER_SIZE - 8) + count * TRACK_SIZE) {
            return Optional.empty();
        }
        long sequence = Integer.toUnsignedLong(b.getInt());
        long timestampMs = b.getLong();
        if (timestampMs < 0) {
            return Optional.empty();
        }
        List<LinkTrack> tracks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int id = b.getInt();
            Identity identity = Identity.fromCode(Byte.toUnsignedInt(b.get()));
            int flags = Byte.toUnsignedInt(b.get());
            double lat = b.getDouble();
            double lon = b.getDouble();
            double speed = b.getFloat();
            double course = b.getFloat();
            if (id < 0 || !(lat >= -90.0 && lat <= 90.0) || !Double.isFinite(lon)
                    || !Double.isFinite(speed) || speed < 0.0 || !Double.isFinite(course)) {
                return Optional.empty();
            }
            tracks.add(new LinkTrack(id, identity, flags, lat, lon, speed, course));
        }
        return Optional.of(new TrackMessage(sequence, timestampMs, tracks));
    }
}
