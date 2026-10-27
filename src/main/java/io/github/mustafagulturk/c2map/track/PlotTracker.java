package io.github.mustafagulturk.c2map.track;

import io.github.mustafagulturk.c2map.net.Plot;
import io.github.mustafagulturk.c2map.net.PlotBatch;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * Multi-target tracker for RDR1 plots, a Java port of radar-target-tracker's TrackManager.
 *
 * <p>Works in the radar's local east/north plane. For every sector batch it predicts all tracks to
 * the batch time with a constant-velocity Kalman filter, gates plot/track pairs with a chi-square
 * (Mahalanobis) gate, assigns them greedily by global nearest neighbour (confirmed tracks win ties),
 * and manages the life cycle: a new tentative track per unused plot, confirmed after
 * {@link Config#confirmHits()} hits, deleted after too many consecutive misses. A miss is only
 * counted when the antenna actually swept the track's azimuth in this batch.
 *
 * <p>Not thread-safe: feed it from a single thread.
 */
public final class PlotTracker {

    private static final double TWO_PI = 2 * Math.PI;
    /** A sensor clock jump backwards larger than this is treated as a radar restart. */
    private static final long CLOCK_RESET_US = 10_000_000L;

    /**
     * Tracker parameters; defaults ({@link #defaults()}) match radar-target-tracker's TrackerConfig.
     *
     * @param accelNoisePsd        Kalman process noise q, m²/s³
     * @param rangeSigmaM          sensor range accuracy (1 sigma)
     * @param azimuthSigmaRad      sensor bearing accuracy (1 sigma)
     * @param initialSpeedSigmaMps velocity uncertainty of a new track
     * @param gateThreshold        chi-square gate for confirmed tracks, 2 degrees of freedom
     * @param tentativeGateThreshold chi-square gate for tentative tracks (tighter: keeps clutter
     *                             from building tracks)
     * @param confirmHits          hits needed to confirm a track
     * @param maxMissesTentative   tentative track is deleted after more misses than this
     * @param maxMissesConfirmed   confirmed track is deleted after more misses than this
     * @param maxPositionSigmaM    tracks whose position becomes this vague are deleted
     * @param maxSpeedMps          fastest plausible target; limits two-point track initiation
     * @param missLagRad           a miss is judged on the window [start - lag, end - lag] of each
     *                             sector, so a target just past the sector end, which may be
     *                             reported in the next sector, is not counted as missed
     */
    public record Config(double accelNoisePsd, double rangeSigmaM, double azimuthSigmaRad,
            double initialSpeedSigmaMps, double gateThreshold, double tentativeGateThreshold, int confirmHits,
            int maxMissesTentative, int maxMissesConfirmed, double maxPositionSigmaM, double maxSpeedMps,
            double missLagRad) {

        public static Config defaults() {
            return new Config(300.0, 30.0, Math.toRadians(0.3), 200.0, 16.0, 9.21, 3, 0, 4, 3000.0, 600.0,
                    Math.toRadians(1.5));
        }
    }

    /**
     * Read-only view of a track.
     *
     * @param id             track number, starts at 1
     * @param confirmed      whether the track has been confirmed
     * @param east           east of the radar, metres
     * @param north          north of the radar, metres
     * @param ve             east velocity, m/s
     * @param vn             north velocity, m/s
     * @param positionSigmaM sqrt of the larger position variance
     * @param hits           number of associated plots
     * @param misses         consecutive misses
     * @param lastUpdateUs   sensor time of the last associated plot
     */
    public record TrackState(int id, boolean confirmed, double east, double north, double ve, double vn,
            double positionSigmaM, int hits, int misses, long lastUpdateUs) {

        public double speedMps() {
            return Math.hypot(ve, vn);
        }

        /** Course over ground, degrees clockwise from north, [0, 360). */
        public double courseDeg() {
            double d = Math.toDegrees(Math.atan2(ve, vn)) % 360.0;
            return d < 0 ? d + 360.0 : d;
        }
    }

    /**
     * Outcome of one batch.
     *
     * @param updated confirmed tracks that received a plot in this batch (including newly confirmed)
     * @param deleted ids of confirmed tracks deleted in this batch
     */
    public record Result(List<TrackState> updated, List<Integer> deleted) {

        public Result {
            updated = List.copyOf(updated);
            deleted = List.copyOf(deleted);
        }
    }

    private record Candidate(int track, int meas, double distanceSq, boolean confirmed) {
    }

    /**
     * Confirmed tracks first, then closest pair first. Mahalanobis distance favours vague tracks, so
     * without the priority a fresh tentative track (wide velocity prior) would steal plots from the
     * confirmed track it shadows.
     */
    private static final Comparator<Candidate> CANDIDATE_ORDER = Comparator
            .comparing((Candidate c) -> !c.confirmed())
            .thenComparingDouble(Candidate::distanceSq);

    private static final class Track {
        final int id;
        final CvKalmanFilter filter;
        long timeUs;
        long lastUpdateUs;
        boolean confirmed;
        int hits = 1;
        int misses;

        Track(int id, CvKalmanFilter filter, long timeUs) {
            this.id = id;
            this.filter = filter;
            this.timeUs = timeUs;
            this.lastUpdateUs = timeUs;
        }
    }

    private final Config config;
    private final List<Track> tracks = new ArrayList<>();
    private int nextId = 1;
    private long lastBatchUs = Long.MIN_VALUE;

    public PlotTracker() {
        this(Config.defaults());
    }

    public PlotTracker(Config config) {
        this.config = config;
    }

    public Config config() {
        return config;
    }

    /** Processes one sector batch; see {@link Result}. */
    public Result process(PlotBatch batch) {
        List<Integer> deleted = new ArrayList<>();
        long t = batch.timestampUs();
        if (lastBatchUs != Long.MIN_VALUE && t < lastBatchUs - CLOCK_RESET_US) {
            deleted.addAll(reset());
        }
        lastBatchUs = Math.max(lastBatchUs, t);

        for (Track track : tracks) {
            if (t > track.timeUs) {
                track.filter.predict((t - track.timeUs) * 1e-6);
                track.timeUs = t;
            }
        }

        List<Plot> plots = batch.plots();
        int m = plots.size();
        double[] zx = new double[m];
        double[] zy = new double[m];
        double[][] r = new double[m][];
        for (int i = 0; i < m; i++) {
            Plot p = plots.get(i);
            double s = Math.sin(p.azimuthRad());
            double c = Math.cos(p.azimuthRad());
            zx[i] = p.rangeM() * s;
            zy[i] = p.rangeM() * c;
            r[i] = polarCovariance(p.rangeM(), s, c);
        }

        // Every gated pair, then greedy global nearest neighbour. Tracks outside the current sector
        // are gated too: a target on a sector boundary may be reported in either batch.
        int n = tracks.size();
        List<Candidate> candidates = new ArrayList<>();
        for (int ti = 0; ti < n; ti++) {
            Track track = tracks.get(ti);
            CvKalmanFilter f = track.filter;
            // A single-plot track has no velocity yet: the next plot must be reachable at max speed.
            double reach = track.hits == 1
                    ? config.maxSpeedMps() * Math.max(0, t - track.lastUpdateUs) * 1e-6 + 3 * f.positionSigma()
                    : Double.MAX_VALUE;
            for (int mi = 0; mi < m; mi++) {
                double d2 = f.mahalanobisSq(zx[mi], zy[mi], r[mi]);
                if (d2 <= (track.confirmed ? config.gateThreshold() : config.tentativeGateThreshold())
                        && (reach == Double.MAX_VALUE || Math.hypot(zx[mi] - f.x[0], zy[mi] - f.x[1]) <= reach)) {
                    candidates.add(new Candidate(ti, mi, d2, track.confirmed));
                }
            }
        }
        candidates.sort(CANDIDATE_ORDER);

        boolean[] trackUsed = new boolean[n];
        boolean[] measUsed = new boolean[m];
        List<TrackState> updated = new ArrayList<>();
        for (Candidate c : candidates) {
            int ti = c.track();
            int mi = c.meas();
            if (trackUsed[ti] || measUsed[mi]) {
                continue;
            }
            Track track = tracks.get(ti);
            if (!track.filter.update(zx[mi], zy[mi], r[mi])) {
                continue;
            }
            trackUsed[ti] = true;
            measUsed[mi] = true;
            track.lastUpdateUs = t;
            track.misses = 0;
            track.hits++;
            if (!track.confirmed && track.hits >= config.confirmHits()) {
                track.confirmed = true;
            }
            if (track.confirmed) {
                updated.add(toState(track));
            }
        }

        // A track only misses if the antenna actually looked where it should be.
        for (int ti = 0; ti < n; ti++) {
            if (trackUsed[ti]) {
                continue;
            }
            double[] x = tracks.get(ti).filter.x;
            double az = normalize(Math.atan2(x[0], x[1]));
            double lag = config.missLagRad();
            if (angleInSector(az, batch.sectorStartRad() - lag, batch.sectorEndRad() - lag)) {
                tracks.get(ti).misses++;
            }
        }

        for (Iterator<Track> it = tracks.iterator(); it.hasNext();) {
            Track track = it.next();
            if (shouldDelete(track)) {
                if (track.confirmed) {
                    deleted.add(track.id);
                }
                it.remove();
            }
        }

        double velocityVariance = config.initialSpeedSigmaMps() * config.initialSpeedSigmaMps();
        for (int mi = 0; mi < m; mi++) {
            if (!measUsed[mi]) {
                tracks.add(new Track(nextId++,
                        new CvKalmanFilter(zx[mi], zy[mi], r[mi], velocityVariance, config.accelNoisePsd()), t));
            }
        }
        return new Result(updated, deleted);
    }

    /** Drops all tracks; returns the ids of the confirmed ones. Track numbering continues. */
    public List<Integer> reset() {
        List<Integer> ids = new ArrayList<>();
        for (Track track : tracks) {
            if (track.confirmed) {
                ids.add(track.id);
            }
        }
        tracks.clear();
        lastBatchUs = Long.MIN_VALUE;
        return ids;
    }

    /** All tracks, tentative and confirmed. */
    public List<TrackState> tracks() {
        List<TrackState> out = new ArrayList<>(tracks.size());
        for (Track track : tracks) {
            out.add(toState(track));
        }
        return Collections.unmodifiableList(out);
    }

    public List<TrackState> confirmedTracks() {
        List<TrackState> out = new ArrayList<>();
        for (Track track : tracks) {
            if (track.confirmed) {
                out.add(toState(track));
            }
        }
        return Collections.unmodifiableList(out);
    }

    public int trackCount() {
        return tracks.size();
    }

    private boolean shouldDelete(Track track) {
        int maxMisses = track.confirmed ? config.maxMissesConfirmed() : config.maxMissesTentative();
        return track.misses > maxMisses || track.filter.positionSigma() > config.maxPositionSigmaM();
    }

    private double[] polarCovariance(double range, double sin, double cos) {
        // J = d(x, y)/d(r, a) for x = r sin a, y = r cos a; R = J diag(sr², sa²) J^T
        double sr2 = config.rangeSigmaM() * config.rangeSigmaM();
        double sa2 = config.azimuthSigmaRad() * config.azimuthSigmaRad();
        double j00 = sin;
        double j01 = range * cos;
        double j10 = cos;
        double j11 = -range * sin;
        double r00 = j00 * j00 * sr2 + j01 * j01 * sa2;
        double r01 = j00 * j10 * sr2 + j01 * j11 * sa2;
        double r11 = j10 * j10 * sr2 + j11 * j11 * sa2;
        return new double[] {r00, r01, r01, r11};
    }

    private static TrackState toState(Track track) {
        double[] x = track.filter.x;
        return new TrackState(track.id, track.confirmed, x[0], x[1], x[2], x[3], track.filter.positionSigma(),
                track.hits, track.misses, track.lastUpdateUs);
    }

    static double normalize(double rad) {
        double a = rad % TWO_PI;
        if (a < 0) {
            a += TWO_PI;
        }
        return a >= TWO_PI ? 0.0 : a;
    }

    /** True if {@code angle} lies in the clockwise sector from {@code start} to {@code end}. */
    static boolean angleInSector(double angle, double start, double end) {
        return normalize(angle - start) <= normalize(end - start);
    }
}
