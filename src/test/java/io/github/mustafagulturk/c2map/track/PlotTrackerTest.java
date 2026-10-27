package io.github.mustafagulturk.c2map.track;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mustafagulturk.c2map.net.Plot;
import io.github.mustafagulturk.c2map.net.PlotBatch;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PlotTrackerTest {

    private static final int SECTORS = 36;
    private static final double SCAN_S = 2.0;
    private static final double MAX_RANGE = 40_000;

    /** Straight-line target, alive in [startS, endS). */
    private record Target(double x0, double y0, double vx, double vy, double startS, double endS) {
        double x(double t) {
            return x0 + vx * t;
        }

        double y(double t) {
            return y0 + vy * t;
        }

        boolean alive(double t) {
            return t >= startS && t < endS;
        }
    }

    /** Radar sensor model like radar_simulator: Pd, Gaussian noise, Poisson clutter. */
    private static final class Sensor {
        final Random rng;
        final double pd;
        final double clutterPerScan;
        long sequence;

        Sensor(long seed, double pd, double clutterPerScan) {
            this.rng = new Random(seed);
            this.pd = pd;
            this.clutterPerScan = clutterPerScan;
        }

        PlotBatch sector(List<Target> targets, int index, double t) {
            double width = 2 * Math.PI / SECTORS;
            double start = index * width;
            double end = (index + 1) * width;
            List<Plot> plots = new ArrayList<>();
            for (Target tg : targets) {
                if (!tg.alive(t)) {
                    continue;
                }
                double x = tg.x(t);
                double y = tg.y(t);
                double r = Math.hypot(x, y);
                double az = PlotTracker.normalize(Math.atan2(x, y));
                if (r > MAX_RANGE || !PlotTracker.angleInSector(az, start, end) || rng.nextDouble() > pd) {
                    continue;
                }
                plots.add(new Plot(r + rng.nextGaussian() * 30.0,
                        PlotTracker.normalize(az + rng.nextGaussian() * Math.toRadians(0.3)), 10));
            }
            int clutter = poisson(clutterPerScan / SECTORS);
            for (int i = 0; i < clutter; i++) {
                plots.add(new Plot(Math.sqrt(rng.nextDouble()) * MAX_RANGE, start + rng.nextDouble() * width, 3));
            }
            return new PlotBatch(sequence++, Math.round(t * 1e6), start, PlotTracker.normalize(end), plots);
        }

        int poisson(double mean) {
            double l = Math.exp(-mean);
            int k = 0;
            double p = rng.nextDouble();
            while (p > l) {
                k++;
                p *= rng.nextDouble();
            }
            return k;
        }
    }

    private static double sectorTime(int scan, int sector) {
        return scan * SCAN_S + (sector + 1) * SCAN_S / SECTORS;
    }

    private static PlotTracker.TrackState nearest(List<PlotTracker.TrackState> tracks, double x, double y) {
        PlotTracker.TrackState best = null;
        double bestD = Double.MAX_VALUE;
        for (PlotTracker.TrackState s : tracks) {
            double d = Math.hypot(s.east() - x, s.north() - y);
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        return bestD < 500 ? best : null;
    }

    @ParameterizedTest
    @ValueSource(longs = {12345, 1, 2, 3, 4, 5, 6, 7})
    void tracksTargetsInClutterWithoutSwapsAndDeletesLostTarget(long seed) {
        // A flies east, B flies north; they cross near (0, 10 km) about 3 s apart. C vanishes at 60 s.
        Target a = new Target(-14_000, 10_000, 200, 0, 0, 1e9);
        Target b = new Target(0, -4_600, 0, 200, 0, 1e9);
        Target c = new Target(20_000, -15_000, -150, 50, 0, 60);
        Target ship = new Target(-25_000, -20_000, 8, 4, 0, 1e9);
        List<Target> targets = List.of(a, b, c, ship);

        PlotTracker tracker = new PlotTracker();
        Sensor sensor = new Sensor(seed, 0.9, 20);
        Set<Integer> falseConfirmed = new HashSet<>();
        Integer idA = null;
        Integer idB = null;
        Integer idC = null;
        boolean cDeleted = false;
        int scans = 60; // 120 s
        for (int scan = 0; scan < scans; scan++) {
            for (int s = 0; s < SECTORS; s++) {
                double t = sectorTime(scan, s);
                PlotTracker.Result result = tracker.process(sensor.sector(targets, s, t));
                for (PlotTracker.TrackState st : result.updated()) {
                    assertTrue(st.confirmed());
                    double best = Double.MAX_VALUE;
                    for (Target tg : targets) {
                        if (t >= tg.startS() && t < tg.endS() + 2 * SCAN_S) { // just-vanished C still counts
                            best = Math.min(best, Math.hypot(tg.x(t) - st.east(), tg.y(t) - st.north()));
                        }
                    }
                    if (best > 1000) {
                        falseConfirmed.add(st.id());
                    }
                }
                if (idC != null && result.deleted().contains(idC)) {
                    cDeleted = true;
                    assertTrue(t > 60 && t < 60 + 6 * SCAN_S, "C deleted at " + t);
                }
            }
            double t = sectorTime(scan, SECTORS - 1);
            List<PlotTracker.TrackState> confirmed = tracker.confirmedTracks();
            if (scan == 15) { // 32 s: well before the crossing
                PlotTracker.TrackState ta = nearest(confirmed, a.x(t), a.y(t));
                PlotTracker.TrackState tb = nearest(confirmed, b.x(t), b.y(t));
                PlotTracker.TrackState tc = nearest(confirmed, c.x(t), c.y(t));
                assertNotNull(ta, "A confirmed");
                assertNotNull(tb, "B confirmed");
                assertNotNull(tc, "C confirmed");
                assertNotNull(nearest(confirmed, ship.x(t), ship.y(t)), "ship confirmed");
                idA = ta.id();
                idB = tb.id();
                idC = tc.id();
                // q = 300 m²/s³ (manoeuvre tolerant) makes single velocity estimates noisy.
                assertEquals(200, ta.speedMps(), 50);
                assertEquals(90, ta.courseDeg(), 15);
                assertEquals(0, Math.min(tb.courseDeg(), 360 - tb.courseDeg()), 15);
            }
            if (scan >= 15) {
                // The same ids follow the same targets through the crossing (t ~ 70 s). A track
                // that just coasted through missed detections may lag; check freshly updated ones.
                for (PlotTracker.TrackState st : confirmed) {
                    Target truth = st.id() == idA ? a : st.id() == idB ? b : null;
                    if (truth == null) {
                        continue;
                    }
                    double tu = st.lastUpdateUs() * 1e-6;
                    double tol = st.misses() == 0 && t - tu < SCAN_S ? 300 : 1500;
                    assertEquals(truth.x(t), st.east(), tol, "track " + st.id() + " east at " + t);
                    assertEquals(truth.y(t), st.north(), tol, "track " + st.id() + " north at " + t);
                }
            }
        }
        double tEnd = sectorTime(scans - 1, SECTORS - 1);
        List<PlotTracker.TrackState> confirmed = tracker.confirmedTracks();
        Set<Integer> ids = new HashSet<>();
        confirmed.forEach(s -> ids.add(s.id()));
        assertTrue(ids.contains(idA), "A still tracked");
        assertTrue(ids.contains(idB), "B still tracked");
        assertTrue(cDeleted, "C deleted");
        assertFalse(ids.contains(idC));
        assertEquals(idA, nearest(confirmed, a.x(tEnd), a.y(tEnd)).id());
        assertEquals(idB, nearest(confirmed, b.x(tEnd), b.y(tEnd)).id());

        // 60 scans x 20 false alarms = 1200 clutter plots; at most one may become a confirmed track.
        assertTrue(falseConfirmed.size() <= 1, "false confirmed tracks: " + falseConfirmed);
        assertTrue(confirmed.size() <= 3 + 1, "confirmed at end: " + confirmed.size());
    }

    @Test
    void missOnlyCountedWhenSectorCoversTrack() {
        PlotTracker tracker = new PlotTracker();
        // Target at azimuth ~45 deg; three hits confirm it.
        for (int i = 0; i < 3; i++) {
            tracker.process(new PlotBatch(i, i * 2_000_000L + 1, Math.toRadians(40), Math.toRadians(50),
                    List.of(new Plot(10_000, Math.toRadians(45), 10))));
        }
        assertEquals(1, tracker.confirmedTracks().size());
        // Many empty sectors elsewhere: no misses.
        for (int i = 0; i < 20; i++) {
            tracker.process(new PlotBatch(10 + i, 6_000_000L + i * 50_000L, Math.toRadians(100), Math.toRadians(110),
                    List.of()));
        }
        assertEquals(0, tracker.confirmedTracks().get(0).misses());
        // Five sweeps over the track without a plot delete it (max 4 misses for confirmed tracks).
        List<Integer> deleted = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            deleted.addAll(tracker.process(new PlotBatch(100 + i, 8_000_000L + i * 2_000_000L, Math.toRadians(40),
                    Math.toRadians(50), List.of())).deleted());
        }
        assertEquals(1, deleted.size());
        assertEquals(0, tracker.trackCount());
    }

    @Test
    void sectorWrapAroundNorth() {
        assertTrue(PlotTracker.angleInSector(Math.toRadians(2), Math.toRadians(355), Math.toRadians(5)));
        assertTrue(PlotTracker.angleInSector(Math.toRadians(358), Math.toRadians(355), Math.toRadians(5)));
        assertFalse(PlotTracker.angleInSector(Math.toRadians(10), Math.toRadians(355), Math.toRadians(5)));
    }

    @Test
    void sensorRestartResetsTracks() {
        PlotTracker tracker = new PlotTracker();
        for (int i = 0; i < 3; i++) {
            tracker.process(new PlotBatch(i, 100_000_000L + i * 2_000_000L, 0, 0.2,
                    List.of(new Plot(5_000, 0.1, 10))));
        }
        assertEquals(1, tracker.confirmedTracks().size());
        int id = tracker.confirmedTracks().get(0).id();
        PlotTracker.Result r = tracker.process(new PlotBatch(0, 1_000_000L, 0, 0.2, List.of()));
        assertEquals(List.of(id), r.deleted());
        assertEquals(0, tracker.trackCount());
    }
}
