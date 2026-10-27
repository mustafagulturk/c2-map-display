package io.github.mustafagulturk.c2map.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mustafagulturk.c2map.track.Identity;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrackMessageCodecTest {

    private static final LinkTrack HOSTILE = new LinkTrack(42, Identity.HOSTILE, 0, 38.5, 27.25, 230.0, 87.5);

    @Test
    void layoutMatchesDocumentation() {
        byte[] b = TrackMessageCodec.encode(new TrackMessage(5, 1_700_000_000_000L, List.of(HOSTILE)));
        assertEquals(TrackMessageCodec.HEADER_SIZE + TrackMessageCodec.TRACK_SIZE, b.length);
        ByteBuffer buf = ByteBuffer.wrap(b);
        assertEquals(0x54524B31, buf.getInt(0));
        assertArrayEquals(new byte[] {'T', 'R', 'K', '1', 1, 0, 0, 1}, Arrays.copyOf(b, 8));
        assertEquals(5, buf.getInt(8));
        assertEquals(1_700_000_000_000L, buf.getLong(12));
        assertEquals(42, buf.getInt(20));
        assertEquals(Identity.HOSTILE.code(), b[24]);
        assertEquals(0, b[25]);
        assertEquals(38.5, buf.getDouble(26));
        assertEquals(27.25, buf.getDouble(34));
        assertEquals(230.0f, buf.getFloat(42));
        assertEquals(87.5f, buf.getFloat(46));
    }

    @Test
    void roundTrip() {
        LinkTrack ended = new LinkTrack(Integer.MAX_VALUE, Identity.FRIEND, LinkTrack.FLAG_ENDED, -10, 179.9, 0, 359.5);
        TrackMessage in = new TrackMessage(0xFFFF_FFFFL, 123, List.of(HOSTILE, ended));
        TrackMessage out = TrackMessageCodec.decode(TrackMessageCodec.encode(in)).orElseThrow();
        assertEquals(in, out);
        assertTrue(out.tracks().get(1).ended());
    }

    @Test
    void unknownIdentityCodeReadsAsUnknown() {
        byte[] b = TrackMessageCodec.encode(new TrackMessage(1, 1, List.of(HOSTILE)));
        b[24] = 9;
        assertEquals(Identity.UNKNOWN, TrackMessageCodec.decode(b).orElseThrow().tracks().get(0).identity());
    }

    @Test
    void rejectsMalformed() {
        byte[] good = TrackMessageCodec.encode(new TrackMessage(1, 1, List.of(HOSTILE)));
        assertTrue(TrackMessageCodec.decode(Arrays.copyOf(good, 19)).isEmpty());
        assertTrue(TrackMessageCodec.decode(Arrays.copyOf(good, good.length - 1)).isEmpty());
        assertTrue(TrackMessageCodec.decode(Arrays.copyOf(good, good.length + 1)).isEmpty());

        byte[] magic = good.clone();
        magic[0] = 'X';
        assertTrue(TrackMessageCodec.decode(magic).isEmpty());
        byte[] version = good.clone();
        version[4] = 2;
        assertTrue(TrackMessageCodec.decode(version).isEmpty());
        byte[] lat = good.clone();
        ByteBuffer.wrap(lat).putDouble(26, 91.0);
        assertTrue(TrackMessageCodec.decode(lat).isEmpty());
        byte[] lon = good.clone();
        ByteBuffer.wrap(lon).putDouble(34, Double.NaN);
        assertTrue(TrackMessageCodec.decode(lon).isEmpty());
        byte[] speed = good.clone();
        ByteBuffer.wrap(speed).putFloat(42, -1f);
        assertTrue(TrackMessageCodec.decode(speed).isEmpty());
        byte[] course = good.clone();
        ByteBuffer.wrap(course).putFloat(46, Float.POSITIVE_INFINITY);
        assertTrue(TrackMessageCodec.decode(course).isEmpty());
        byte[] id = good.clone();
        ByteBuffer.wrap(id).putInt(20, -5);
        assertTrue(TrackMessageCodec.decode(id).isEmpty());
    }

    @Test
    void splitsManyTracksUnderMtu() {
        List<LinkTrack> tracks = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            tracks.add(new LinkTrack(i, Identity.fromCode(i % 3), 0, 38 + i * 1e-4, 27, 100, i % 360));
        }
        assertEquals(46, TrackMessageCodec.MAX_TRACKS_PER_DATAGRAM);
        assertThrows(IllegalArgumentException.class,
                () -> TrackMessageCodec.encode(new TrackMessage(0, 0, tracks.subList(0, 47))));
        List<byte[]> datagrams = TrackMessageCodec.encodeSplit(10, 99, tracks);
        assertEquals((5000 + 45) / 46, datagrams.size());
        int n = 0;
        long seq = 10;
        for (byte[] d : datagrams) {
            assertTrue(d.length <= TrackMessageCodec.MAX_DATAGRAM_SIZE);
            TrackMessage m = TrackMessageCodec.decode(d).orElseThrow();
            assertEquals(seq++, m.sequence());
            assertEquals(99, m.timestampMs());
            for (LinkTrack t : m.tracks()) {
                assertEquals(n++, t.id());
            }
        }
        assertEquals(5000, n);
        assertEquals(1, TrackMessageCodec.encodeSplit(0, 0, List.of()).size());
    }
}
