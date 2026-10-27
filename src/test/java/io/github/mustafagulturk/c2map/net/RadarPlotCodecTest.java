package io.github.mustafagulturk.c2map.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class RadarPlotCodecTest {

    /** Hand-built RDR1 datagram: sequence 258, t = 2 s, sector [0.5, 1.0] rad, two plots. */
    private static byte[] fixture() {
        int r1 = Float.floatToIntBits(1000.0f);
        int a1 = Float.floatToIntBits(0.75f);
        int d1 = Float.floatToIntBits(12.5f);
        int r2 = Float.floatToIntBits(39000.0f);
        int a2 = Float.floatToIntBits(0.5f);
        int d2 = Float.floatToIntBits(-3.0f);
        int s = Float.floatToIntBits(0.5f);
        int e = Float.floatToIntBits(1.0f);
        return bytes(
                'R', 'D', 'R', '1',
                1, 0,
                0x00, 0x02,
                0x00, 0x00, 0x01, 0x02,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x1E, 0x84, 0x80, // 2_000_000 us
                s >>> 24, s >>> 16, s >>> 8, s,
                e >>> 24, e >>> 16, e >>> 8, e,
                r1 >>> 24, r1 >>> 16, r1 >>> 8, r1, a1 >>> 24, a1 >>> 16, a1 >>> 8, a1, d1 >>> 24, d1 >>> 16, d1 >>> 8, d1,
                r2 >>> 24, r2 >>> 16, r2 >>> 8, r2, a2 >>> 24, a2 >>> 16, a2 >>> 8, a2, d2 >>> 24, d2 >>> 16, d2 >>> 8, d2);
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static PlotBatch fixtureBatch() {
        return new PlotBatch(258, 2_000_000L, 0.5, 1.0,
                List.of(new Plot(1000.0, 0.75, 12.5), new Plot(39000.0, 0.5, -3.0)));
    }

    @Test
    void decodesHandBuiltDatagram() {
        PlotBatch b = RadarPlotCodec.decode(fixture()).orElseThrow();
        assertEquals(fixtureBatch(), b);
    }

    @Test
    void encodesByteExact() {
        assertArrayEquals(fixture(), RadarPlotCodec.encode(fixtureBatch()));
    }

    @Test
    void unsignedSequenceAndOffsetDecode() {
        PlotBatch in = new PlotBatch(0xFFFF_FFF0L, 1L << 40, 6.0, 0.1, List.of());
        byte[] enc = RadarPlotCodec.encode(in);
        byte[] padded = new byte[enc.length + 7];
        System.arraycopy(enc, 0, padded, 3, enc.length);
        PlotBatch out = RadarPlotCodec.decode(padded, 3, enc.length).orElseThrow();
        assertEquals(0xFFFF_FFF0L, out.sequence());
        assertEquals(1L << 40, out.timestampUs());
        assertEquals((float) 6.0, (float) out.sectorStartRad());
    }

    @Test
    void rejectsMalformed() {
        byte[] good = fixture();
        assertTrue(RadarPlotCodec.decode(Arrays.copyOf(good, 27)).isEmpty(), "short header");
        assertTrue(RadarPlotCodec.decode(Arrays.copyOf(good, good.length - 1)).isEmpty(), "truncated");
        assertTrue(RadarPlotCodec.decode(Arrays.copyOf(good, good.length + 1)).isEmpty(), "trailing");
        assertTrue(RadarPlotCodec.decode((byte[]) null).isEmpty());

        byte[] magic = good.clone();
        magic[3] = '2';
        assertTrue(RadarPlotCodec.decode(magic).isEmpty(), "magic");

        byte[] version = good.clone();
        version[4] = 2;
        assertTrue(RadarPlotCodec.decode(version).isEmpty(), "version");

        byte[] nanSector = good.clone();
        ByteBuffer.wrap(nanSector).putFloat(20, Float.NaN);
        assertTrue(RadarPlotCodec.decode(nanSector).isEmpty(), "sector NaN");

        byte[] infAmp = good.clone();
        ByteBuffer.wrap(infAmp).putFloat(28 + 8, Float.POSITIVE_INFINITY);
        assertTrue(RadarPlotCodec.decode(infAmp).isEmpty(), "amplitude inf");

        byte[] negRange = good.clone();
        ByteBuffer.wrap(negRange).putFloat(40, -1.0f);
        assertTrue(RadarPlotCodec.decode(negRange).isEmpty(), "negative range");
    }

    @Test
    void splitKeepsDatagramsUnderMtu() {
        List<Plot> plots = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            plots.add(new Plot(i * 10.0, 0.1, 1.0));
        }
        PlotBatch big = new PlotBatch(7, 99, 0, 0.2, plots);
        assertThrows(IllegalArgumentException.class, () -> RadarPlotCodec.encode(big));
        List<PlotBatch> parts = RadarPlotCodec.split(big);
        assertEquals(3, parts.size());
        int total = 0;
        for (PlotBatch p : parts) {
            byte[] enc = RadarPlotCodec.encode(p);
            assertTrue(enc.length <= RadarPlotCodec.MAX_DATAGRAM_SIZE);
            assertEquals(7, p.sequence());
            total += RadarPlotCodec.decode(enc).orElseThrow().plots().size();
        }
        assertEquals(300, total);
        assertEquals(1, RadarPlotCodec.split(new PlotBatch(1, 1, 0, 1, List.of())).size());
    }
}
