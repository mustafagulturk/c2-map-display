package io.github.mustafagulturk.c2map.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mustafagulturk.c2map.geo.Geodesy;
import io.github.mustafagulturk.c2map.track.Identity;
import io.github.mustafagulturk.c2map.track.PlotTracker;
import io.github.mustafagulturk.c2map.track.RadarSite;
import io.github.mustafagulturk.c2map.track.TrackSnapshot;
import io.github.mustafagulturk.c2map.track.TrackStore;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class IngestDispatcherTest {

    private final RadarSite site = RadarSite.DEFAULT;
    private final TrackStore store = new TrackStore();
    private final IngestDispatcher dispatcher = new IngestDispatcher(site, new PlotTracker(), store);

    private static byte[] plots(long seq, long tUs, double startDeg, double endDeg, Plot... plots) {
        return RadarPlotCodec.encode(new PlotBatch(seq, tUs, Math.toRadians(startDeg), Math.toRadians(endDeg),
                List.of(plots)));
    }

    @Test
    void radarPlotsBecomeRadarTracks() {
        // A target 10 km out at 45 deg moving north-east at ~141 m/s, seen once per 2 s scan.
        List<TrackSnapshot> published = new ArrayList<>();
        store.addListener((prev, cur) -> published.add(cur));
        for (int i = 0; i < 5; i++) {
            double east = 7071 + 100 * 2 * i;
            double north = 7071 + 100 * 2 * i;
            Plot p = new Plot(Math.hypot(east, north), Math.atan2(east, north), 10);
            dispatcher.accept(Channel.RADAR_PLOTS, plots(i * 36L, i * 2_000_000L, 40, 50, p), 1_000_000 + i * 2000L);
        }
        IngestDispatcher.ChannelStats stats = dispatcher.stats(Channel.RADAR_PLOTS);
        assertEquals(5, stats.datagrams());
        assertEquals(5, stats.items());
        assertEquals(0, stats.errors());
        assertEquals(144, stats.lastSequence());
        assertEquals(4 * 35, stats.lostPackets(), "sequence jumps of 36 imply 35 lost each");

        assertEquals(1, store.size());
        TrackSnapshot t = store.snapshot().get(0);
        assertEquals(TrackSnapshot.Source.RADAR, t.source());
        assertEquals(Identity.UNKNOWN, t.identity());
        assertEquals(1_008_000, t.updatedAtMs());
        assertEquals(45, t.courseDeg(), 5);
        assertEquals(141, t.speedMps(), 20);
        double[] en = site.toEastNorth(t.position());
        assertEquals(7871, en[0], 50);
        assertEquals(7871, en[1], 50);
        assertEquals(3, published.size(), "confirmed on the third hit, then updated twice");
    }

    @Test
    void linkTracksGoStraightToStore() {
        LinkTrack hostile = new LinkTrack(9, Identity.HOSTILE, 0, 38.6, 27.3, 250, 180);
        LinkTrack friend = new LinkTrack(10, Identity.FRIEND, 0, 38.5, 27.0, 10, 45);
        dispatcher.accept(Channel.TRACKS, TrackMessageCodec.encode(new TrackMessage(1, 5, List.of(hostile, friend))), 100);
        assertEquals(2, store.size());
        TrackSnapshot h = store.get("L-9").orElseThrow();
        assertEquals(Identity.HOSTILE, h.identity());
        assertEquals(38.6, h.position().lat(), 1e-12);
        assertEquals(250, h.speedMps(), 1e-6);
        assertEquals(100, h.updatedAtMs());

        LinkTrack ended = new LinkTrack(10, Identity.FRIEND, LinkTrack.FLAG_ENDED, 38.5, 27.0, 10, 45);
        dispatcher.accept(Channel.TRACKS, TrackMessageCodec.encode(new TrackMessage(4, 6, List.of(ended))), 200);
        assertTrue(store.get("L-10").isEmpty());
        IngestDispatcher.ChannelStats stats = dispatcher.stats(Channel.TRACKS);
        assertEquals(2, stats.datagrams());
        assertEquals(3, stats.items());
        assertEquals(2, stats.lostPackets());
        assertEquals(4, stats.lastSequence());
    }

    @Test
    void countsDecodeErrorsPerChannel() {
        dispatcher.accept(Channel.RADAR_PLOTS, new byte[] {1, 2, 3}, 0);
        dispatcher.accept(Channel.TRACKS, plots(1, 1, 0, 10), 0); // RDR1 on the TRK1 port
        dispatcher.accept(Channel.TRACKS, new byte[0], 0);
        assertEquals(1, dispatcher.stats(Channel.RADAR_PLOTS).errors());
        assertEquals(2, dispatcher.stats(Channel.TRACKS).errors());
        assertEquals(-1, dispatcher.stats(Channel.TRACKS).lastSequence());
        assertEquals(0, store.size());
    }

    @Test
    void splitSectorIsNotLossAndRestartIsNotLoss() {
        dispatcher.accept(Channel.RADAR_PLOTS, plots(10, 1, 0, 10), 0);
        dispatcher.accept(Channel.RADAR_PLOTS, plots(10, 1, 0, 10), 0);
        dispatcher.accept(Channel.RADAR_PLOTS, plots(11, 2, 10, 20), 0);
        dispatcher.accept(Channel.RADAR_PLOTS, plots(0, 3, 20, 30), 0);
        dispatcher.accept(Channel.RADAR_PLOTS, plots(2, 4, 30, 40), 0);
        assertEquals(1, dispatcher.stats(Channel.RADAR_PLOTS).lostPackets());
        dispatcher.reset();
        assertEquals(0, dispatcher.stats(Channel.RADAR_PLOTS).datagrams());
        assertEquals(-1, dispatcher.stats(Channel.RADAR_PLOTS).lastSequence());
    }

    @Test
    void radarTrackPositionUsesSiteGeometry() {
        // Single plot straight north at 20 km: after confirmation the track sits 20 km north of the site.
        for (int i = 0; i < 3; i++) {
            dispatcher.accept(Channel.RADAR_PLOTS, plots(i, i * 2_000_000L, 355, 5, new Plot(20_000, 0, 10)), i);
        }
        TrackSnapshot t = store.snapshot().get(0);
        assertEquals(20_000, Geodesy.distanceMeters(site.position(), t.position()), 30);
        assertEquals(0, Math.min(Geodesy.initialBearingDeg(site.position(), t.position()),
                360 - Geodesy.initialBearingDeg(site.position(), t.position())), 0.5);
    }
}
