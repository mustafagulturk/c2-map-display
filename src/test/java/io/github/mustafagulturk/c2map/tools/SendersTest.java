package io.github.mustafagulturk.c2map.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mustafagulturk.c2map.geo.GeoPoint;
import io.github.mustafagulturk.c2map.geo.Geodesy;
import io.github.mustafagulturk.c2map.net.LinkTrack;
import io.github.mustafagulturk.c2map.net.PlotBatch;
import io.github.mustafagulturk.c2map.net.RadarPlotCodec;
import io.github.mustafagulturk.c2map.net.TrackMessageCodec;
import io.github.mustafagulturk.c2map.track.Identity;
import io.github.mustafagulturk.c2map.track.PlotTracker;
import java.util.List;
import org.junit.jupiter.api.Test;

class SendersTest {

    @Test
    void plotSenderMatchesRadarSimulatorTiming() {
        PlotSender sim = new PlotSender(5, 20, 3, 40_000);
        long lastSeq = -1;
        for (int i = 0; i < 36; i++) {
            PlotBatch b = sim.nextSector();
            assertEquals(lastSeq + 1, b.sequence());
            lastSeq = b.sequence();
            assertEquals(Math.toRadians(10 * i), b.sectorStartRad(), 1e-9);
            assertEquals((i + 1) * 2.0 / 36 * 1e6, b.timestampUs(), 2.0);
            for (int k = 1; k < b.plots().size(); k++) {
                assertTrue(b.plots().get(k).rangeM() >= b.plots().get(k - 1).rangeM(), "range order");
            }
            RadarPlotCodec.decode(RadarPlotCodec.encode(b)).orElseThrow();
        }
        assertEquals(2.0, sim.timeS(), 1e-9);
    }

    @Test
    void trackerFollowsPlotSenderTargets() {
        PlotSender sim = new PlotSender(5, 20, 11, 40_000);
        PlotTracker tracker = new PlotTracker();
        for (int i = 0; i < 36 * 40; i++) { // 80 s
            tracker.process(sim.nextSector());
        }
        int confirmed = tracker.confirmedTracks().size();
        // Five targets (one may have left coverage and been replaced recently); clutter adds little.
        assertTrue(confirmed >= 4 && confirmed <= 7, "confirmed tracks: " + confirmed);
    }

    @Test
    void trackSenderStaysAroundCentreWithRequestedMix() {
        GeoPoint centre = new GeoPoint(38.42, 27.14);
        TrackSender sender = new TrackSender(2000, centre, 60_000, 5, 0.25, 0.25);
        int hostile = 0;
        int friend = 0;
        for (LinkTrack t : sender.tracks(false)) {
            if (t.identity() == Identity.HOSTILE) {
                hostile++;
            } else if (t.identity() == Identity.FRIEND) {
                friend++;
            }
        }
        assertEquals(500, hostile, 80);
        assertEquals(500, friend, 80);
        for (int i = 0; i < 600; i++) { // 10 minutes at 1 s steps
            sender.step(1.0);
        }
        List<LinkTrack> tracks = sender.tracks(false);
        assertEquals(2000, tracks.size());
        for (LinkTrack t : tracks) {
            double d = Geodesy.distanceMeters(centre, new GeoPoint(t.lat(), t.lon()));
            assertTrue(d < 60_000 + 40_000, "track " + t.id() + " wandered off: " + d);
        }
        List<byte[]> datagrams = TrackMessageCodec.encodeSplit(0, 1, tracks);
        assertEquals((2000 + 45) / 46, datagrams.size());
        assertTrue(sender.tracks(true).get(0).ended());
    }

    @Test
    void argumentParsing() {
        assertEquals("5", TrackSender.parseArgs(new String[] {"--port", "5"}, "--host --port").get("--port"));
        assertNull(TrackSender.parseArgs(new String[] {"--help"}, "--port"));
        assertThrows(IllegalArgumentException.class, () -> TrackSender.parseArgs(new String[] {"--bogus", "1"}, "--port"));
        assertThrows(IllegalArgumentException.class, () -> TrackSender.parseArgs(new String[] {"--port"}, "--port"));
    }
}
