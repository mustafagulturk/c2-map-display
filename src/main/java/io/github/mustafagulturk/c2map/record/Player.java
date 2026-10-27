package io.github.mustafagulturk.c2map.record;

import io.github.mustafagulturk.c2map.net.DatagramSink;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Replays a recording into a {@link DatagramSink} on its own daemon thread, keeping the original
 * inter-arrival timing scaled by a speed factor. Each datagram is passed with its original
 * recorded arrival time, so downstream code sees consistent data time; {@link #currentDataTimeMs()}
 * is a smooth replay clock in that same time base (use it as {@code nowMs} for map layers).
 *
 * <p>All control methods are thread-safe. The sink and the end-of-file callback run on the
 * player thread. After {@link #stop()} returns, the sink is not called again (unless {@code stop}
 * was called from the sink itself).
 */
public final class Player {

    public enum State { STOPPED, PLAYING, PAUSED, FINISHED }

    public static final double MIN_SPEED = 0.5;
    public static final double MAX_SPEED = 16.0;

    private static final System.Logger LOG = System.getLogger(Player.class.getName());

    private final DatagramSink sink;
    private final Object lock = new Object();

    // guarded by lock
    private RecordingInfo info;
    private State state = State.STOPPED;
    private double speed = 1.0;
    private long generation;
    private Thread thread;
    /** Data time at {@link #anchorNanos}; while not playing, the frozen position. */
    private long anchorDataMs;
    private long anchorNanos;
    /** Lower and upper bound of the position: last delivered and next pending datagram time. */
    private long deliveredMs;
    private long pendingMs = Long.MAX_VALUE;
    private long sinkErrors;
    private IOException lastError;
    private Runnable onFinished;

    public Player(DatagramSink sink) {
        this.sink = Objects.requireNonNull(sink);
    }

    /** Scans a recording; same as {@link RecordingInfo#read}. */
    public static RecordingInfo read(Path file) throws IOException {
        return RecordingInfo.read(file);
    }

    /** Stops any current replay and loads {@code file}, positioned at its start. */
    public RecordingInfo open(Path file) throws IOException {
        stop();
        RecordingInfo i = RecordingInfo.read(file);
        synchronized (lock) {
            info = i;
            resetPositionLocked();
            lastError = null;
            sinkErrors = 0;
        }
        return i;
    }

    /** Starts from the beginning (when stopped or finished) or resumes (when paused). */
    public void play() {
        synchronized (lock) {
            if (info == null) {
                throw new IllegalStateException("no recording open");
            }
            switch (state) {
                case PLAYING -> {
                    return;
                }
                case PAUSED -> {
                    anchorNanos = System.nanoTime();
                    state = State.PLAYING;
                    lock.notifyAll();
                }
                case STOPPED, FINISHED -> {
                    resetPositionLocked();
                    anchorNanos = System.nanoTime();
                    state = State.PLAYING;
                    long gen = ++generation;
                    Path file = info.path();
                    thread = new Thread(() -> run(file, gen), "c2-replay");
                    thread.setDaemon(true);
                    thread.start();
                }
            }
        }
    }

    /** Freezes the replay clock; {@link #play()} resumes. */
    public void pause() {
        synchronized (lock) {
            if (state == State.PLAYING) {
                anchorDataMs = positionLocked();
                state = State.PAUSED;
                lock.notifyAll();
            }
        }
    }

    /** Ends the replay and rewinds to the start. */
    public void stop() {
        Thread t;
        synchronized (lock) {
            generation++;
            t = thread;
            thread = null;
            state = State.STOPPED;
            resetPositionLocked();
            lock.notifyAll();
        }
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Changes the speed factor, clamped to [{@value #MIN_SPEED}, {@value #MAX_SPEED}]; takes effect immediately. */
    public void setSpeed(double factor) {
        if (!Double.isFinite(factor)) {
            throw new IllegalArgumentException("speed " + factor);
        }
        synchronized (lock) {
            if (state == State.PLAYING) {
                anchorDataMs = positionLocked();
                anchorNanos = System.nanoTime();
            }
            speed = Math.max(MIN_SPEED, Math.min(MAX_SPEED, factor));
            lock.notifyAll();
        }
    }

    public double speed() {
        synchronized (lock) {
            return speed;
        }
    }

    public State state() {
        synchronized (lock) {
            return state;
        }
    }

    /** The open recording, or null. */
    public RecordingInfo info() {
        synchronized (lock) {
            return info;
        }
    }

    /** Replay clock in the recording's time base (ms); 0 if nothing is open. */
    public long currentDataTimeMs() {
        synchronized (lock) {
            return info == null ? 0 : positionLocked();
        }
    }

    /** Data time elapsed since the start of the recording, ms. */
    public long elapsedMs() {
        synchronized (lock) {
            return info == null ? 0 : positionLocked() - info.firstTimeMs();
        }
    }

    /** Data duration of the open recording, ms. */
    public long durationMs() {
        synchronized (lock) {
            return info == null ? 0 : info.durationMs();
        }
    }

    /** Fraction played, 0..1. */
    public double progress() {
        synchronized (lock) {
            if (info == null) {
                return 0;
            }
            if (state == State.FINISHED) {
                return 1;
            }
            long d = info.durationMs();
            if (d <= 0) {
                return 0;
            }
            return Math.max(0, Math.min(1, (positionLocked() - info.firstTimeMs()) / (double) d));
        }
    }

    /** Called on the player thread when the end of the recording is reached (not on {@link #stop()}). */
    public void setOnFinished(Runnable callback) {
        synchronized (lock) {
            onFinished = callback;
        }
    }

    /** Read error that ended the last replay early, or null. */
    public IOException lastError() {
        synchronized (lock) {
            return lastError;
        }
    }

    /** Number of exceptions thrown by the sink during replay (each is logged and skipped). */
    public long sinkErrorCount() {
        synchronized (lock) {
            return sinkErrors;
        }
    }

    // ---------------------------------------------------------------- internals

    private void resetPositionLocked() {
        long start = info == null ? 0 : info.firstTimeMs();
        anchorDataMs = start;
        deliveredMs = start;
        pendingMs = Long.MAX_VALUE;
    }

    private long positionLocked() {
        long pos = anchorDataMs;
        if (state == State.PLAYING) {
            pos += (long) ((System.nanoTime() - anchorNanos) / 1e6 * speed);
        }
        return Math.max(deliveredMs, Math.min(pos, pendingMs));
    }

    private void run(Path file, long gen) {
        try (RecordingReader reader = new RecordingReader(file)) {
            RecordingReader.Datagram d;
            while ((d = reader.next()) != null) {
                if (!waitFor(d.timeMs(), gen)) {
                    return;
                }
                try {
                    sink.accept(d.channel(), d.data(), d.timeMs());
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "replay sink failed", e);
                    synchronized (lock) {
                        sinkErrors++;
                    }
                }
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.ERROR, "replay of " + file + " failed", e);
            synchronized (lock) {
                if (generation == gen) {
                    lastError = e;
                }
            }
        } catch (InterruptedException e) {
            return;
        }
        Runnable cb;
        synchronized (lock) {
            if (generation != gen) {
                return;
            }
            state = State.FINISHED;
            thread = null;
            pendingMs = Long.MAX_VALUE;
            anchorDataMs = Math.max(deliveredMs, info.lastTimeMs());
            deliveredMs = anchorDataMs;
            cb = onFinished;
        }
        if (cb != null) {
            cb.run();
        }
    }

    /** Blocks until the replay clock reaches {@code timeMs}; false if this replay was stopped. */
    private boolean waitFor(long timeMs, long gen) throws InterruptedException {
        synchronized (lock) {
            pendingMs = Math.max(timeMs, deliveredMs);
            while (true) {
                if (generation != gen) {
                    return false;
                }
                if (state == State.PAUSED) {
                    lock.wait();
                    continue;
                }
                long pos = anchorDataMs + (long) ((System.nanoTime() - anchorNanos) / 1e6 * speed);
                if (pos >= timeMs) {
                    break;
                }
                long waitMs = (long) Math.ceil((timeMs - pos) / speed);
                lock.wait(Math.max(1, waitMs));
            }
            deliveredMs = Math.max(deliveredMs, timeMs);
            pendingMs = Long.MAX_VALUE;
            return true;
        }
    }
}
