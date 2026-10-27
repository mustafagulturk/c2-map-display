package io.github.mustafagulturk.c2map.tools;

import io.github.mustafagulturk.c2map.event.EventLog;
import io.github.mustafagulturk.c2map.geo.Geodesy;
import io.github.mustafagulturk.c2map.geo.GeoPoint;
import io.github.mustafagulturk.c2map.net.Channel;
import io.github.mustafagulturk.c2map.net.IngestDispatcher;
import io.github.mustafagulturk.c2map.net.LinkTrack;
import io.github.mustafagulturk.c2map.net.PlotBatch;
import io.github.mustafagulturk.c2map.net.RadarPlotCodec;
import io.github.mustafagulturk.c2map.net.TrackMessageCodec;
import io.github.mustafagulturk.c2map.track.Identity;
import io.github.mustafagulturk.c2map.track.PlotTracker;
import io.github.mustafagulturk.c2map.track.RadarSite;
import io.github.mustafagulturk.c2map.track.TrackListener;
import io.github.mustafagulturk.c2map.track.TrackSnapshot;
import io.github.mustafagulturk.c2map.track.TrackStore;
import io.github.mustafagulturk.c2map.zone.ZoneMonitor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Times the non-UI path a datagram takes (decode, tracker, track store, zone check) without the
 * network, so the numbers do not depend on UDP buffering. Prints one table row per case.
 *
 * <p>Usage: {@code ./gradlew ingestBenchmark}
 */
public final class IngestBenchmark {

    private IngestBenchmark() {
    }

    public static void main(String[] args) {
        System.out.println("| Durum | Datagram | Ort. (µs) | p99 (µs) | Kapasite |");
        System.out.println("| --- | ---: | ---: | ---: | ---: |");
        for (int targets : new int[] {6, 30, 100}) {
            radarCase(targets, 20);
        }
        for (int tracks : new int[] {1000, 5000}) {
            linkCase(tracks);
        }
    }

    private static Pipeline pipeline() {
        TrackStore store = new TrackStore();
        ZoneMonitor zones = new ZoneMonitor(new EventLog());
        GeoPoint c = RadarSite.DEFAULT.position();
        for (int i = 0; i < 4; i++) {
            List<GeoPoint> square = new ArrayList<>();
            GeoPoint centre = Geodesy.destination(c, i * 90 + 45, 20_000);
            for (int k = 0; k < 4; k++) {
                square.add(Geodesy.destination(centre, k * 90 + 45, 7_000));
            }
            zones.createZone(square);
        }
        store.addListener(new TrackListener() {
            @Override
            public void onTrackUpdated(TrackSnapshot previous, TrackSnapshot current) {
                zones.onTrackUpdated(previous, current);
            }

            @Override
            public void onTrackDropped(TrackSnapshot last) {
                zones.onTrackDropped(last);
            }
        });
        return new Pipeline(new IngestDispatcher(RadarSite.DEFAULT, new PlotTracker(), store), store);
    }

    private record Pipeline(IngestDispatcher dispatcher, TrackStore store) {
    }

    /** Radar plots: 5 minutes of a rotating radar, timed per datagram. */
    private static void radarCase(int targets, double clutter) {
        Pipeline p = pipeline();
        PlotSender sim = new PlotSender(targets, clutter, 42, RadarSite.DEFAULT.maxRangeM());
        List<byte[]> datagrams = new ArrayList<>();
        for (int i = 0; i < 36 * 150; i++) {
            for (PlotBatch part : RadarPlotCodec.split(sim.nextSector())) {
                datagrams.add(RadarPlotCodec.encode(part));
            }
        }
        long[] ns = run(p, Channel.RADAR_PLOTS, datagrams, 18);
        print(String.format(Locale.ROOT, "Radar plot, %d hedef + %.0f clutter/tur (%d iz)", targets, clutter,
                p.store().size()), ns, 18);
    }

    /** Data-link tracks: every track updated once per second, 46 tracks per datagram. */
    private static void linkCase(int tracks) {
        Pipeline p = pipeline();
        List<byte[]> datagrams = new ArrayList<>();
        GeoPoint c = RadarSite.DEFAULT.position();
        long seq = 0;
        for (int second = 0; second < 30; second++) {
            List<LinkTrack> batch = new ArrayList<>(tracks);
            for (int id = 0; id < tracks; id++) {
                double bearing = (id * 137.5) % 360;
                GeoPoint start = Geodesy.destination(c, bearing, 2_000 + (id % 30) * 1_500);
                GeoPoint pos = Geodesy.destination(start, (bearing + 90) % 360, second * 200.0);
                batch.add(new LinkTrack(id + 1, Identity.fromCode(id % 3), 0, pos.lat(), pos.lon(), 200,
                        (bearing + 90) % 360));
            }
            List<byte[]> parts = TrackMessageCodec.encodeSplit(seq, 1_000_000L + second * 1000L, batch);
            seq += parts.size();
            datagrams.addAll(parts);
        }
        double perSecond = Math.ceil(tracks / 46.0);
        long[] ns = run(p, Channel.TRACKS, datagrams, perSecond);
        print(String.format(Locale.ROOT, "Veri bağı, %d iz × 1 Hz", tracks), ns, perSecond);
    }

    private static long[] run(Pipeline p, Channel channel, List<byte[]> datagrams, double perSecond) {
        // Warm up the JIT on the first third, time the rest.
        int warm = datagrams.size() / 3;
        long[] ns = new long[datagrams.size() - warm];
        long t = 0;
        for (int i = 0; i < datagrams.size(); i++) {
            long dataTime = 1_000_000L + (long) (i / perSecond * 1000);
            long s = System.nanoTime();
            p.dispatcher().accept(channel, datagrams.get(i), dataTime);
            long d = System.nanoTime() - s;
            if (i >= warm) {
                ns[(int) t++] = d;
            }
        }
        return ns;
    }

    private static void print(String name, long[] ns, double perSecond) {
        long[] sorted = ns.clone();
        Arrays.sort(sorted);
        double avgUs = Arrays.stream(ns).average().orElse(0) / 1000.0;
        double p99Us = sorted[(int) Math.min(sorted.length - 1, Math.round(sorted.length * 0.99))] / 1000.0;
        double load = avgUs * perSecond / 1e6 * 100;
        System.out.printf(Locale.ROOT, "| %s | %.0f/s | %.1f | %.1f | tek çekirdeğin %%%.2f'i |%n", name, perSecond,
                avgUs, p99Us, load);
    }
}
