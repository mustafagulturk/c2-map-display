package io.github.mustafagulturk.c2map.tools;

import io.github.mustafagulturk.c2map.net.Plot;
import io.github.mustafagulturk.c2map.net.PlotBatch;
import io.github.mustafagulturk.c2map.net.RadarPlotCodec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.locks.LockSupport;

/**
 * Small Java stand-in for radar-target-tracker's {@code radar_simulator}: a rotating 2D radar
 * (2 s scan, 36 sectors) that sends RDR1 plot datagrams with Pd 0.9, Gaussian range/bearing noise
 * (30 m, 0.3 deg) and Poisson clutter, so the display can be demoed without building the C++
 * project. Targets that leave coverage are replaced by new inbound ones.
 *
 * <pre>
 * --host 127.0.0.1 --port 5005 --targets 6 --clutter 20 (false alarms per scan) --seed 1
 * --duration 0 (seconds of sensor time, 0 = until stopped) --speedup 1 --range-km 40
 * </pre>
 */
public final class PlotSender {

    static final double SCAN_PERIOD_S = 2.0;
    static final int SECTORS = 36;
    static final double PD = 0.9;
    static final double RANGE_SIGMA_M = 30.0;
    static final double AZIMUTH_SIGMA_RAD = Math.toRadians(0.3);
    private static final double TWO_PI = 2 * Math.PI;

    /** Coordinated-turn target in radar-centred east/north metres. */
    static final class Target {
        double east;
        double north;
        double speed;
        double headingRad;
        double turnRateRadS; // positive = clockwise
        double rcsDb;

        void advance(double dt) {
            headingRad += turnRateRadS * dt;
            east += speed * Math.sin(headingRad) * dt;
            north += speed * Math.cos(headingRad) * dt;
        }

        double range() {
            return Math.hypot(east, north);
        }
    }

    private final SplittableRandom rng;
    private final double maxRangeM;
    private final double clutterPerScan;
    private final List<Target> targets = new ArrayList<>();
    private long sequence;
    private double timeS;
    private int sectorIndex;

    PlotSender(int targetCount, double clutterPerScan, long seed, double maxRangeM) {
        this.rng = new SplittableRandom(seed);
        this.maxRangeM = maxRangeM;
        this.clutterPerScan = clutterPerScan;
        double r = maxRangeM / 1000;
        // Same hand-made set as radar_simulator's standard scenario, then random inbound ones.
        double[][] standard = {
            {0.8 * r, 300, 230, 100, 0, 10}, // airliners on crossing courses
            {0.8 * r, 60, 220, 255, 0, 10},
            {0.45 * r, 150, 280, 0, 3, 8},   // fighter in a 3 deg/s turn
            {0.3 * r, 220, 12, 45, 0, 20},   // slow surface vessel
            {0.95 * r, 20, 450, 200, 0, 0},  // fast inbound, low RCS
        };
        for (int i = 0; i < targetCount; i++) {
            if (i < standard.length) {
                double[] s = standard[i];
                targets.add(make(s[0], s[1], s[2], s[3], s[4], s[5]));
            } else {
                targets.add(randomInbound());
            }
        }
    }

    private static Target make(double rangeKm, double bearingDeg, double speed, double headingDeg,
            double turnDegS, double rcsDb) {
        Target t = new Target();
        double b = Math.toRadians(bearingDeg);
        t.east = rangeKm * 1000 * Math.sin(b);
        t.north = rangeKm * 1000 * Math.cos(b);
        t.speed = speed;
        t.headingRad = Math.toRadians(headingDeg);
        t.turnRateRadS = Math.toRadians(turnDegS);
        t.rcsDb = rcsDb;
        return t;
    }

    private Target randomInbound() {
        double bearing = rng.nextDouble() * 360;
        double heading = (bearing + 180 + (rng.nextDouble() * 100 - 50) + 360) % 360;
        double rangeKm = (0.7 + rng.nextDouble() * 0.25) * maxRangeM / 1000;
        double speed = 80 + rng.nextDouble() * 240;
        double turn = rng.nextDouble() < 0.3 ? rng.nextDouble() * 4 - 2 : 0;
        return make(rangeKm, bearing, speed, heading, turn, 10);
    }

    /** Advances one sector and returns its batch (not yet split). */
    PlotBatch nextSector() {
        double dt = SCAN_PERIOD_S / SECTORS;
        timeS += dt;
        for (int i = 0; i < targets.size(); i++) {
            Target t = targets.get(i);
            t.advance(dt);
            if (t.range() > maxRangeM * 1.05) {
                targets.set(i, randomInbound());
            }
        }
        double width = TWO_PI / SECTORS;
        double start = sectorIndex * width;
        double end = normalize((sectorIndex + 1) * width);
        sectorIndex = (sectorIndex + 1) % SECTORS;

        List<Plot> plots = new ArrayList<>();
        for (Target t : targets) {
            double range = t.range();
            double az = normalize(Math.atan2(t.east, t.north));
            if (range > maxRangeM || !inSector(az, start, end) || rng.nextDouble() > PD) {
                continue;
            }
            plots.add(new Plot(Math.max(0, range + gaussian() * RANGE_SIGMA_M),
                    normalize(az + gaussian() * AZIMUTH_SIGMA_RAD), t.rcsDb + gaussian() * 2));
        }
        int clutter = poisson(clutterPerScan * width / TWO_PI);
        for (int i = 0; i < clutter; i++) {
            plots.add(new Plot(Math.sqrt(rng.nextDouble()) * maxRangeM, normalize(start + rng.nextDouble() * width),
                    3 + gaussian() * 2));
        }
        // Real radars report in range order; also hides which plots are clutter.
        plots.sort(Comparator.comparingDouble(Plot::rangeM));
        return new PlotBatch(sequence++ & 0xFFFF_FFFFL, Math.round(timeS * 1e6), start, end, plots);
    }

    private double gaussian() {
        return rng.nextGaussian();
    }

    private int poisson(double mean) {
        if (mean <= 0) {
            return 0;
        }
        double l = Math.exp(-mean);
        int k = 0;
        double p = rng.nextDouble();
        while (p > l) {
            k++;
            p *= rng.nextDouble();
        }
        return k;
    }

    static double normalize(double rad) {
        double a = rad % TWO_PI;
        if (a < 0) {
            a += TWO_PI;
        }
        return a >= TWO_PI ? 0.0 : a;
    }

    static boolean inSector(double angle, double start, double end) {
        return normalize(angle - start) <= normalize(end - start);
    }

    double timeS() {
        return timeS;
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> a = TrackSender.parseArgs(args,
                "--host --port --targets --clutter --seed --duration --speedup --range-km");
        if (a == null) {
            System.out.println("Usage: PlotSender [--host 127.0.0.1] [--port 5005] [--targets 6] [--clutter 20]"
                    + " [--seed 1] [--duration 0] [--speedup 1] [--range-km 40]");
            return;
        }
        String host = a.getOrDefault("--host", "127.0.0.1");
        int port = Integer.parseInt(a.getOrDefault("--port", "5005"));
        int targets = Integer.parseInt(a.getOrDefault("--targets", "6"));
        double clutter = Double.parseDouble(a.getOrDefault("--clutter", "20"));
        long seed = Long.parseLong(a.getOrDefault("--seed", "1"));
        double duration = Double.parseDouble(a.getOrDefault("--duration", "0"));
        double speedup = Double.parseDouble(a.getOrDefault("--speedup", "1"));
        double rangeM = Double.parseDouble(a.getOrDefault("--range-km", "40")) * 1000;
        if (targets < 0 || clutter < 0 || !(speedup > 0) || !(rangeM > 0)) {
            throw new IllegalArgumentException("invalid arguments");
        }

        PlotSender sim = new PlotSender(targets, clutter, seed, rangeM);
        InetSocketAddress target = new InetSocketAddress(host, port);
        System.out.printf(Locale.ROOT, "RDR1 -> %s, %d targets, %.0f false alarms/scan, %d sectors per %.0f s scan,"
                + " speed-up %.1f%n", target, targets, clutter, SECTORS, SCAN_PERIOD_S, speedup);
        long sectorNs = (long) (SCAN_PERIOD_S / SECTORS / speedup * 1e9);
        try (DatagramChannel ch = DatagramChannel.open()) {
            ch.connect(target);
            long next = System.nanoTime();
            long datagrams = 0;
            int scans = 0;
            while (duration <= 0 || sim.timeS() < duration) {
                PlotBatch batch = sim.nextSector();
                for (PlotBatch part : RadarPlotCodec.split(batch)) {
                    try {
                        ch.write(ByteBuffer.wrap(RadarPlotCodec.encode(part)));
                    } catch (PortUnreachableException e) {
                        // Nobody listening yet; keep the radar turning.
                    }
                    datagrams++;
                }
                if (sim.sectorIndex == 0 && ++scans % 15 == 0) {
                    System.out.printf(Locale.ROOT, "t=%.0f s, %d datagrams%n", sim.timeS(), datagrams);
                }
                next += sectorNs;
                long sleep = next - System.nanoTime();
                if (sleep > 0) {
                    LockSupport.parkNanos(sleep);
                } else if (sleep < -1_000_000_000L) {
                    next = System.nanoTime();
                }
            }
        }
    }
}
