package io.github.mustafagulturk.c2map.record;

import io.github.mustafagulturk.c2map.net.Channel;
import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * Summary of a recording file.
 *
 * @param path             the file
 * @param version          format version
 * @param startEpochMs     wall-clock time recording started
 * @param firstTimeMs      earliest datagram time (0 if empty)
 * @param lastTimeMs       latest datagram time (0 if empty)
 * @param datagramCount    number of complete datagrams
 * @param countsByChannel  complete datagrams per channel (every channel present, possibly 0)
 * @param truncated        the file ends with a partial or corrupt record, which is ignored
 */
public record RecordingInfo(
        Path path,
        int version,
        long startEpochMs,
        long firstTimeMs,
        long lastTimeMs,
        long datagramCount,
        Map<Channel, Long> countsByChannel,
        boolean truncated) {

    public RecordingInfo {
        countsByChannel = Map.copyOf(countsByChannel);
    }

    /** Data time span, ms. */
    public long durationMs() {
        return lastTimeMs - firstTimeMs;
    }

    public long count(Channel channel) {
        return countsByChannel.getOrDefault(channel, 0L);
    }

    /** Scans the whole file. */
    public static RecordingInfo read(Path file) throws IOException {
        try (RecordingReader r = new RecordingReader(file)) {
            Map<Channel, Long> counts = new EnumMap<>(Channel.class);
            for (Channel c : Channel.values()) {
                counts.put(c, 0L);
            }
            long first = Long.MAX_VALUE;
            long last = Long.MIN_VALUE;
            long n = 0;
            RecordingReader.Datagram d;
            while ((d = r.next()) != null) {
                first = Math.min(first, d.timeMs());
                last = Math.max(last, d.timeMs());
                counts.merge(d.channel(), 1L, Long::sum);
                n++;
            }
            if (n == 0) {
                first = 0;
                last = 0;
            }
            return new RecordingInfo(file, r.version(), r.startEpochMs(), first, last, n, counts, r.truncated());
        }
    }
}
