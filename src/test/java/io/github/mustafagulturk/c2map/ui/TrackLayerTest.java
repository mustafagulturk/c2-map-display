package io.github.mustafagulturk.c2map.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mustafagulturk.c2map.geo.GeoPoint;
import io.github.mustafagulturk.c2map.map.MapViewport;
import io.github.mustafagulturk.c2map.track.Identity;
import io.github.mustafagulturk.c2map.track.TrackSnapshot;
import java.util.ArrayList;
import java.util.List;
import javafx.geometry.Point2D;
import org.junit.jupiter.api.Test;

class TrackLayerTest {

    /** Simple equirectangular view: 100 m per pixel around (38.4, 27.1), 1000 x 800 px. */
    private static final class FakeViewport implements MapViewport {
        static final GeoPoint CENTRE = new GeoPoint(38.4, 27.1);
        static final double MPP = 100;
        static final double M_PER_DEG = 111_195;

        @Override
        public Point2D toScreen(GeoPoint p) {
            double x = (p.lon() - CENTRE.lon()) * M_PER_DEG * Math.cos(Math.toRadians(CENTRE.lat())) / MPP + 500;
            double y = (CENTRE.lat() - p.lat()) * M_PER_DEG / MPP + 400;
            return new Point2D(x, y);
        }

        @Override
        public GeoPoint toGeo(double x, double y) {
            double lon = CENTRE.lon() + (x - 500) * MPP / (M_PER_DEG * Math.cos(Math.toRadians(CENTRE.lat())));
            double lat = CENTRE.lat() - (y - 400) * MPP / M_PER_DEG;
            return new GeoPoint(lat, lon);
        }

        @Override
        public double metersPerPixel() {
            return MPP;
        }

        @Override
        public double zoom() {
            return 10;
        }

        @Override
        public double width() {
            return 1000;
        }

        @Override
        public double height() {
            return 800;
        }
    }

    private static TrackSnapshot track(int id, GeoPoint p) {
        return new TrackSnapshot(id, TrackSnapshot.Source.LINK, p, 216.0, 87.4, Identity.HOSTILE, List.of(), 0);
    }

    @Test
    void hitTestFindsNearestWithinRadius() {
        FakeViewport vp = new FakeViewport();
        List<TrackSnapshot> tracks = new ArrayList<>();
        tracks.add(track(1, vp.toGeo(200, 200)));
        tracks.add(track(2, vp.toGeo(208, 200)));
        tracks.add(track(3, vp.toGeo(-500, 200))); // off-screen
        TrackLayer layer = new TrackLayer(() -> tracks);

        assertEquals("L-1", layer.trackAt(vp, 202, 201).orElseThrow().key());
        assertEquals("L-2", layer.trackAt(vp, 207, 200).orElseThrow().key());
        assertEquals("L-2", layer.trackAt(vp, 219, 200).orElseThrow().key());
        assertFalse(layer.trackAt(vp, 221, 200).isPresent());
        assertFalse(layer.trackAt(vp, 500, 500).isPresent());
        assertFalse(layer.trackAt(vp, -500, 200).isPresent(), "culled outside the view");
    }

    @Test
    void selection() {
        TrackLayer layer = new TrackLayer(List::of);
        layer.setSelected("R-4");
        assertEquals("R-4", layer.selected());
        layer.setSelected(null);
        assertEquals(null, layer.selected());
        assertTrue(layer.showTrails());
    }

    @Test
    void labelFormat() {
        assertEquals("L-7  420kt 087°", Symbology.label(track(7, new GeoPoint(38, 27))));
        TrackSnapshot slow = new TrackSnapshot(12, TrackSnapshot.Source.RADAR, new GeoPoint(38, 27), 0, 359.7,
                Identity.UNKNOWN, List.of(), 0);
        assertEquals("R-12  0kt 000°", Symbology.label(slow));
        assertEquals(1.0, Symbology.knots(1852.0 / 3600.0), 1e-12);
    }

    @Test
    void airOrSurfaceFromSpeed() {
        assertTrue(Symbology.isAir(track(1, new GeoPoint(38, 27))));
        TrackSnapshot ship = new TrackSnapshot(2, TrackSnapshot.Source.LINK, new GeoPoint(38, 27), 8, 0,
                Identity.FRIEND, List.of(), 0);
        assertFalse(Symbology.isAir(ship));
    }
}
