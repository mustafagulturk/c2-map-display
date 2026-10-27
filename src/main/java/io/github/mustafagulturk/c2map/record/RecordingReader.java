package io.github.mustafagulturk.c2map.record;

import io.github.mustafagulturk.c2map.net.Channel;
import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Sequential reader of a recording file; see {@link RecordingFormat}. Not thread-safe. */
final class RecordingReader implements Closeable {

    /** One stored datagram. */
    record Datagram(long timeMs, Channel channel, byte[] data) {
    }

    private final DataInputStream in;
    private final int version;
    private final long startEpochMs;
    private boolean truncated;

    /** Opens the file and validates the header. */
    RecordingReader(Path file) throws IOException {
        DataInputStream d = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 16));
        try {
            byte[] magic = new byte[RecordingFormat.MAGIC.length];
            d.readFully(magic);
            if (!Arrays.equals(magic, RecordingFormat.MAGIC)) {
                throw new IOException("not a C2 recording: " + file);
            }
            version = d.readUnsignedByte();
            if (version != RecordingFormat.VERSION) {
                throw new IOException("unsupported recording version " + version + ": " + file);
            }
            d.readUnsignedByte();
            startEpochMs = d.readLong();
        } catch (EOFException e) {
            d.close();
            throw new IOException("recording header truncated: " + file, e);
        } catch (IOException e) {
            d.close();
            throw e;
        }
        in = d;
    }

    int version() {
        return version;
    }

    long startEpochMs() {
        return startEpochMs;
    }

    /** True once {@link #next()} has hit a partial or corrupt record. */
    boolean truncated() {
        return truncated;
    }

    /** The next datagram, or null at the end of the file or at a partial / corrupt record. */
    Datagram next() throws IOException {
        int first = in.read();
        if (first < 0) {
            return null;
        }
        try {
            long time = ((long) first << 56) | (readUnsigned56());
            int code = in.readUnsignedByte();
            int length = in.readUnsignedShort();
            if (code >= Channel.values().length) {
                truncated = true;
                return null;
            }
            byte[] data = new byte[length];
            in.readFully(data);
            return new Datagram(time, Channel.fromCode(code), data);
        } catch (EOFException e) {
            truncated = true;
            return null;
        }
    }

    private long readUnsigned56() throws IOException {
        long v = 0;
        for (int i = 0; i < 7; i++) {
            v = (v << 8) | in.readUnsignedByte();
        }
        return v;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
