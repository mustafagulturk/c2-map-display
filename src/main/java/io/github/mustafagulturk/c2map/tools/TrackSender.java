package io.github.mustafagulturk.c2map.tools;

import io.github.mustafagulturk.c2map.geo.GeoPoint;
import io.github.mustafagulturk.c2map.geo.Geodesy;
import io.github.mustafagulturk.c2map.net.LinkTrack;
import io.github.mustafagulturk.c2map.net.TrackMessageCodec;
import io.github.mustafagulturk.c2map.track.Identity;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.locks.LockSupport;

/**
 * Sends synthetic TRK1 track messages over UDP, for demos and performance tests (up to thousands of
 * tracks). Aircraft and ships fly straight legs and gentle turns around a centre point and turn
 * back towards it when they leave the area. All tracks are sent every tick, packed
 * {@value TrackMessageCodec#MAX_TRACKS_PER_DATAGRAM} per datagram and spread over half the tick to
 * avoid bursts. On exit every track is sent once more with the "ended" flag.
 *
 * <pre>
 * --host 127.0.0.1 --port 5006 --tracks 200 --rate 1 --center 38.42,27.14 --radius-km 60
 * --seed 1 --hostile-ratio 0.2 --friend-ratio 0.4 --duration 0 (seconds, 0 = until stopped)
 * </pre>
 */
public final class TrackSender {

    /** Mutable kinematic state of one synthetic track (local east/north metres). */
    private static final class Mover {
        final int id;
        final Identity identity;
        final boolean air;
        double east;
        double north;
        double speed;
        double courseDeg;
        double turnRateDegS;
        double legRemainingS;

        Mover(int id, Identity identity, boolean air) {
            this.id = id;
            this.identity = identity;
            this.air = air;
        }
    }

    private final SplittableRandom rng;
    private final GeoPoint centre;
    private final double radiusM;
    private final List<Mover> movers = new ArrayList<>();
    /** Next TRK1 datagram sequence number (also used by the shutdown hook). */
    private volatile long nextSequence;
    private boolean endSent;

    TrackSender(int count, GeoPoint centre, double radiusM, long seed, double hostileRatio, double friendRatio) {
        this.rng = new SplittableRandom(seed);
        this.centre = centre;
        this.radiusM = radiusM;
        for (int i = 0; i < count; i++) {
            double u = rng.nextDouble();
            Identity identity = u < hostileRatio ? Identity.HOSTILE
                    : u < hostileRatio + friendRatio ? Identity.FRIEND : Identity.UNKNOWN;
            Mover m = new Mover(1000 + i, identity, rng.nextDouble() < 0.75);
            double r = Math.sqrt(rng.nextDouble()) * radiusM;
            double b = rng.nextDouble() * 2 * Math.PI;
            m.east = r * Math.sin(b);
            m.north = r * Math.cos(b);
            m.speed = m.air ? 110 + rng.nextDouble() * 170 : 4 + rng.nextDouble() * 11;
            m.courseDeg = rng.nextDouble() * 360;
            newLeg(m);
            movers.add(m);
        }
    }

    private void newLeg(Mover m) {
        if (rng.nextDouble() < 0.6) {
            m.turnRateDegS = 0;
            m.legRemainingS = 30 + rng.nextDouble() * 90;
        } else {
            double max = m.air ? 3.0 : 0.5;
            double rate = (0.2 + 0.8 * rng.nextDouble()) * max;
            m.turnRateDegS = rng.nextBoolean() ? rate : -rate;
            m.legRemainingS = 10 + rng.nextDouble() * 30;
        }
    }

    /** Advances every track by {@code dt} seconds. */
    void step(double dt) {
        for (Mover m : movers) {
            m.legRemainingS -= dt;
            double r = Math.hypot(m.east, m.north);
            if (r > radiusM) {
                // Outside the area: turn (gently) towards the centre.
                double home = Geodesy.normalizeDeg(Math.toDegrees(Math.atan2(-m.east, -m.north)));
                double diff = ((home - m.courseDeg + 540) % 360) - 180;
                double max = m.air ? 3.0 : 1.0;
                m.turnRateDegS = Math.abs(diff) < 5 ? 0 : Math.copySign(max, diff);
                m.legRemainingS = Math.max(m.legRemainingS, 5);
            } else if (m.legRemainingS <= 0) {
                newLeg(m);
            }
            m.courseDeg = Geodesy.normalizeDeg(m.courseDeg + m.turnRateDegS * dt);
            double c = Math.toRadians(m.courseDeg);
            m.east += m.speed * Math.sin(c) * dt;
            m.north += m.speed * Math.cos(c) * dt;
        }
    }

    List<LinkTrack> tracks(boolean ended) {
        List<LinkTrack> out = new ArrayList<>(movers.size());
        for (Mover m : movers) {
            GeoPoint p = Geodesy.fromEastNorth(centre, m.east, m.north);
            out.add(new LinkTrack(m.id, m.identity, ended ? LinkTrack.FLAG_ENDED : 0, p.lat(), p.lon(), m.speed,
                    m.courseDeg));
        }
        return out;
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> a = parseArgs(args,
                "--host --port --tracks --rate --center --radius-km --seed --hostile-ratio --friend-ratio --duration");
        if (a == null) {
            System.out.println("Usage: TrackSender [--host 127.0.0.1] [--port 5006] [--tracks 200] [--rate 1]"
                    + " [--center 38.42,27.14] [--radius-km 60] [--seed 1] [--hostile-ratio 0.2]"
                    + " [--friend-ratio 0.4] [--duration 0]");
            return;
        }
        String host = a.getOrDefault("--host", "127.0.0.1");
        int port = Integer.parseInt(a.getOrDefault("--port", "5006"));
        int count = Integer.parseInt(a.getOrDefault("--tracks", "200"));
        double rate = Double.parseDouble(a.getOrDefault("--rate", "1"));
        String[] c = a.getOrDefault("--center", "38.42,27.14").split(",");
        GeoPoint centre = new GeoPoint(Double.parseDouble(c[0].trim()), Double.parseDouble(c[1].trim()));
        double radiusM = Double.parseDouble(a.getOrDefault("--radius-km", "60")) * 1000;
        long seed = Long.parseLong(a.getOrDefault("--seed", "1"));
        double hostile = Double.parseDouble(a.getOrDefault("--hostile-ratio", "0.2"));
        double friend = Double.parseDouble(a.getOrDefault("--friend-ratio", "0.4"));
        double duration = Double.parseDouble(a.getOrDefault("--duration", "0"));
        if (count < 0 || !(rate > 0) || hostile < 0 || friend < 0 || hostile + friend > 1) {
            throw new IllegalArgumentException("invalid arguments");
        }

        TrackSender sender = new TrackSender(count, centre, radiusM, seed, hostile, friend);
        InetSocketAddress target = new InetSocketAddress(host, port);
        System.out.printf(Locale.ROOT, "TRK1 -> %s, %d tracks at %.2f Hz around %s, radius %.0f km%n",
                target, count, rate, centre, radiusM / 1000);

        try (DatagramChannel ch = DatagramChannel.open()) {
            ch.connect(target);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> sendEnded(sender, ch)));
            long periodNs = (long) (1e9 / rate);
            long start = System.nanoTime();
            long next = start;
            long sentDatagrams = 0;
            long lastReport = start;
            int ticks = 0;
            while (duration <= 0 || System.nanoTime() - start < duration * 1e9) {
                List<byte[]> datagrams = TrackMessageCodec.encodeSplit(sender.nextSequence,
                        System.currentTimeMillis(), sender.tracks(false));
                sender.nextSequence += datagrams.size();
                // Spread the datagrams over half the period so the receiver's socket buffer copes.
                long spreadNs = periodNs / 2;
                for (int i = 0; i < datagrams.size(); i++) {
                    long due = next + spreadNs * i / datagrams.size();
                    long wait = due - System.nanoTime();
                    if (wait > 0) {
                        LockSupport.parkNanos(wait);
                    }
                    send(ch, datagrams.get(i));
                }
                sentDatagrams += datagrams.size();
                ticks++;
                long now = System.nanoTime();
                if (now - lastReport >= 5_000_000_000L) {
                    System.out.printf(Locale.ROOT, "%d ticks, %d datagrams, %.0f tracks/s%n", ticks, sentDatagrams,
                            ticks * (double) count / ((now - start) / 1e9));
                    lastReport = now;
                }
                next += periodNs;
                long sleep = next - System.nanoTime();
                if (sleep > 0) {
                    LockSupport.parkNanos(sleep);
                } else if (sleep < -periodNs) {
                    next = System.nanoTime(); // fell behind; do not burst to catch up
                }
                sender.step(periodNs / 1e9);
            }
            sendEnded(sender, ch);
        }
    }

    /** Tells receivers to drop every track; runs once, at normal end or from the shutdown hook. */
    private static void sendEnded(TrackSender sender, DatagramChannel ch) {
        synchronized (sender) {
            if (sender.endSent || !ch.isOpen()) {
                return;
            }
            sender.endSent = true;
        }
        try {
            for (byte[] d : TrackMessageCodec.encodeSplit(sender.nextSequence, System.currentTimeMillis(),
                    sender.tracks(true))) {
                send(ch, d);
            }
        } catch (IOException | RuntimeException e) {
            // Best effort at shutdown.
        }
    }

    private static void send(DatagramChannel ch, byte[] data) throws IOException {
        try {
            ch.write(ByteBuffer.wrap(data));
        } catch (PortUnreachableException e) {
            // Nobody listening yet (ICMP port unreachable on a connected socket): keep going.
        }
    }

    /**
     * Parses {@code --key value} pairs; keys must be in {@code allowed} (space separated). Returns
     * null when help was requested.
     */
    static Map<String, String> parseArgs(String[] args, String allowed) {
        List<String> keys = List.of(allowed.split(" "));
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String k = args[i];
            if (k.equals("--help") || k.equals("-h")) {
                return null;
            }
            if (!keys.contains(k) || i + 1 >= args.length) {
                throw new IllegalArgumentException("unknown or incomplete argument: " + k);
            }
            out.put(k, args[++i]);
        }
        return out;
    }
}
