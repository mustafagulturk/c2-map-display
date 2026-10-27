package io.github.mustafagulturk.c2map.net;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Encoder/decoder for the RDR1 plot datagram of radar-target-tracker ({@code src/core/protocol.cpp}).
 * All fields are big-endian:
 *
 * <pre>
 * offset size  field
 * 0      4     magic         'R','D','R','1' (0x52445231)
 * 4      1     version       1
 * 5      1     reserved      0
 * 6      2     plot_count    N (u16)
 * 8      4     sequence      u32, increments per sector; split datagrams share it
 * 12     8     timestamp_us  u64, sensor time at the end of the sector
 * 20     4     sector_start  f32, radians
 * 24     4     sector_end    f32, radians
 * 28     12*N  plots         {range_m f32, azimuth_rad f32, amplitude_db f32}
 * </pre>
 *
 * Azimuth is measured clockwise from north. Malformed input (wrong magic or version, truncated
 * payload, trailing bytes, non-finite values, negative range) is rejected exactly like the C++
 * decoder does.
 */
public final class RadarPlotCodec {

    public static final int MAGIC = 0x52445231;
    public static final int VERSION = 1;
    public static final int HEADER_SIZE = 28;
    public static final int PLOT_SIZE = 12;
    /** Keeps each datagram below a typical Ethernet MTU. */
    public static final int MAX_DATAGRAM_SIZE = 1400;
    public static final int MAX_PLOTS_PER_DATAGRAM = (MAX_DATAGRAM_SIZE - HEADER_SIZE) / PLOT_SIZE;

    private RadarPlotCodec() {
    }

    /**
     * Serialises a batch.
     *
     * @throws IllegalArgumentException if the batch has more than {@link #MAX_PLOTS_PER_DATAGRAM}
     *                                  plots (use {@link #split})
     */
    public static byte[] encode(PlotBatch batch) {
        int n = batch.plots().size();
        if (n > MAX_PLOTS_PER_DATAGRAM) {
            throw new IllegalArgumentException("too many plots for one datagram: " + n);
        }
        ByteBuffer b = ByteBuffer.allocate(HEADER_SIZE + n * PLOT_SIZE);
        b.putInt(MAGIC);
        b.put((byte) VERSION);
        b.put((byte) 0);
        b.putShort((short) n);
        b.putInt((int) batch.sequence());
        b.putLong(batch.timestampUs());
        b.putFloat((float) batch.sectorStartRad());
        b.putFloat((float) batch.sectorEndRad());
        for (Plot p : batch.plots()) {
            b.putFloat((float) p.rangeM());
            b.putFloat((float) p.azimuthRad());
            b.putFloat((float) p.amplitudeDb());
        }
        return b.array();
    }

    public static Optional<PlotBatch> decode(byte[] data) {
        return data == null ? Optional.empty() : decode(data, 0, data.length);
    }

    /** Parses {@code length} bytes at {@code offset}; empty for anything malformed. */
    public static Optional<PlotBatch> decode(byte[] data, int offset, int length) {
        if (data == null || length < HEADER_SIZE) {
            return Optional.empty();
        }
        ByteBuffer b = ByteBuffer.wrap(data, offset, length);
        if (b.getInt() != MAGIC || Byte.toUnsignedInt(b.get()) != VERSION) {
            return Optional.empty();
        }
        b.get(); // reserved
        int count = Short.toUnsignedInt(b.getShort());
        if (b.remaining() != (HEADER_SIZE - 8) + count * PLOT_SIZE) {
            return Optional.empty();
        }
        long sequence = Integer.toUnsignedLong(b.getInt());
        long timestampUs = b.getLong();
        double start = b.getFloat();
        double end = b.getFloat();
        if (!Double.isFinite(start) || !Double.isFinite(end)) {
            return Optional.empty();
        }
        List<Plot> plots = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double range = b.getFloat();
            double az = b.getFloat();
            double amp = b.getFloat();
            if (!Double.isFinite(range) || !Double.isFinite(az) || !Double.isFinite(amp) || range < 0.0) {
                return Optional.empty();
            }
            plots.add(new Plot(range, az, amp));
        }
        return Optional.of(new PlotBatch(sequence, timestampUs, start, end, plots));
    }

    /** Splits a batch into datagram-sized batches sharing sequence, timestamp and sector. */
    public static List<PlotBatch> split(PlotBatch batch) {
        List<Plot> all = batch.plots();
        List<PlotBatch> parts = new ArrayList<>();
        int offset = 0;
        do {
            int n = Math.min(MAX_PLOTS_PER_DATAGRAM, all.size() - offset);
            parts.add(new PlotBatch(batch.sequence(), batch.timestampUs(), batch.sectorStartRad(),
                    batch.sectorEndRad(), all.subList(offset, offset + n)));
            offset += n;
        } while (offset < all.size());
        return parts;
    }
}
