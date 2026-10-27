package io.github.mustafagulturk.c2map.record;

import java.nio.charset.StandardCharsets;

/**
 * Binary layout of a recording file ({@code *.c2rec}). All integers are big-endian.
 *
 * <table>
 *   <caption>File header (16 bytes)</caption>
 *   <tr><th>Offset</th><th>Type</th><th>Field</th></tr>
 *   <tr><td>0</td><td>6 bytes ASCII</td><td>magic {@code "C2REC1"}</td></tr>
 *   <tr><td>6</td><td>u8</td><td>format version, currently 1</td></tr>
 *   <tr><td>7</td><td>u8</td><td>reserved, 0</td></tr>
 *   <tr><td>8</td><td>i64</td><td>recording start, epoch ms (wall clock when {@code start} was called)</td></tr>
 * </table>
 *
 * <table>
 *   <caption>Datagram record (11 + length bytes), repeated until end of file</caption>
 *   <tr><th>Offset</th><th>Type</th><th>Field</th></tr>
 *   <tr><td>0</td><td>i64</td><td>arrival time in ms, exactly as passed to {@code DatagramSink.accept}</td></tr>
 *   <tr><td>8</td><td>u8</td><td>channel code ({@code Channel.ordinal()})</td></tr>
 *   <tr><td>9</td><td>u16</td><td>payload length (0..65535)</td></tr>
 *   <tr><td>11</td><td>length bytes</td><td>raw datagram payload</td></tr>
 * </table>
 *
 * Records are stored in arrival order; times from several receiver threads may be slightly out of
 * order and readers must tolerate that. A truncated last record (crash, power loss) is ignored.
 */
public final class RecordingFormat {

    static final byte[] MAGIC = "C2REC1".getBytes(StandardCharsets.US_ASCII);
    public static final int VERSION = 1;
    public static final int HEADER_SIZE = 16;
    public static final int RECORD_OVERHEAD = 11;
    public static final int MAX_PAYLOAD = 0xFFFF;
    /** Suggested file name extension. */
    public static final String EXTENSION = ".c2rec";

    private RecordingFormat() {
    }
}
