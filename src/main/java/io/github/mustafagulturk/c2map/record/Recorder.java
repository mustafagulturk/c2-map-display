package io.github.mustafagulturk.c2map.record;

import io.github.mustafagulturk.c2map.net.Channel;
import io.github.mustafagulturk.c2map.net.DatagramSink;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Pass-through {@link DatagramSink} that also appends every datagram to a recording file while
 * recording is on (format: {@link RecordingFormat}). Live data always flows on to the downstream
 * sink, whether recording or not, and even if writing fails.
 *
 * <p>Thread-safe: {@link #accept} may be called concurrently from several receiver threads. Writes
 * are buffered and flushed at least once a second and on {@link #stop()}.
 */
public final class Recorder implements DatagramSink {

    private static final System.Logger LOG = System.getLogger(Recorder.class.getName());
    private static final long FLUSH_INTERVAL_MS = 1000;

    private final DatagramSink downstream;
    private final LongSupplier clock;
    private final Object lock = new Object();

    // guarded by lock
    private DataOutputStream out;
    private Path path;
    private long startedAtMs;
    private long lastFlushMs;
    private long datagrams;
    private long bytes;
    private long dropped;
    private IOException lastError;

    public Recorder(DatagramSink downstream) {
        this(downstream, System::currentTimeMillis);
    }

    public Recorder(DatagramSink downstream, LongSupplier clock) {
        this.downstream = Objects.requireNonNull(downstream);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Starts recording into a new file (an existing file is overwritten). Stops a running
     * recording first. Counters are reset.
     */
    public void start(Path file) throws IOException {
        Objects.requireNonNull(file);
        synchronized (lock) {
            closeLocked();
            Path dir = file.toAbsolutePath().getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            OutputStream raw = Files.newOutputStream(file);
            DataOutputStream o = new DataOutputStream(new BufferedOutputStream(raw, 1 << 16));
            long now = clock.getAsLong();
            try {
                o.write(RecordingFormat.MAGIC);
                o.writeByte(RecordingFormat.VERSION);
                o.writeByte(0);
                o.writeLong(now);
                o.flush();
            } catch (IOException e) {
                o.close();
                throw e;
            }
            out = o;
            path = file;
            startedAtMs = now;
            lastFlushMs = now;
            datagrams = 0;
            bytes = RecordingFormat.HEADER_SIZE;
            dropped = 0;
            lastError = null;
        }
    }

    /** Stops recording and closes the file. Does nothing if not recording. */
    public void stop() throws IOException {
        synchronized (lock) {
            IOException e = closeLocked();
            if (e != null) {
                throw e;
            }
        }
    }

    @Override
    public void accept(Channel channel, byte[] data, long timeMs) {
        synchronized (lock) {
            if (out != null) {
                write(channel, data, timeMs);
            }
        }
        downstream.accept(channel, data, timeMs);
    }

    private void write(Channel channel, byte[] data, long timeMs) {
        if (data.length > RecordingFormat.MAX_PAYLOAD) {
            dropped++;
            return;
        }
        try {
            out.writeLong(timeMs);
            out.writeByte(channel.ordinal());
            out.writeShort(data.length);
            out.write(data);
            datagrams++;
            bytes += RecordingFormat.RECORD_OVERHEAD + data.length;
            long now = clock.getAsLong();
            if (now - lastFlushMs >= FLUSH_INTERVAL_MS || now < lastFlushMs) {
                out.flush();
                lastFlushMs = now;
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.ERROR, "recording to " + path + " failed, recording stopped", e);
            lastError = e;
            dropped++;
            try {
                out.close();
            } catch (IOException ignored) {
                // already failing
            }
            out = null;
        }
    }

    private IOException closeLocked() {
        if (out == null) {
            return null;
        }
        IOException err = null;
        try {
            out.close();
        } catch (IOException e) {
            err = e;
            lastError = e;
        }
        out = null;
        return err;
    }

    public boolean isRecording() {
        synchronized (lock) {
            return out != null;
        }
    }

    /** File of the current or last recording, or null. */
    public Path path() {
        synchronized (lock) {
            return path;
        }
    }

    /** Wall-clock start of the current or last recording, epoch ms (0 if never started). */
    public long startedAtMs() {
        synchronized (lock) {
            return startedAtMs;
        }
    }

    /** Datagrams written in the current or last recording. */
    public long datagramCount() {
        synchronized (lock) {
            return datagrams;
        }
    }

    /** File size in bytes of the current or last recording (including the header). */
    public long byteCount() {
        synchronized (lock) {
            return bytes;
        }
    }

    /** Datagrams that could not be written (oversized or after an I/O error). */
    public long droppedCount() {
        synchronized (lock) {
            return dropped;
        }
    }

    /** The write error that stopped the recording, or null. */
    public IOException lastError() {
        synchronized (lock) {
            return lastError;
        }
    }
}
