package io.github.mustafagulturk.c2map.zone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mustafagulturk.c2map.geo.GeoPoint;
import io.github.mustafagulturk.c2map.geo.Geodesy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RestrictedZoneTest {

    static final GeoPoint ANKARA = new GeoPoint(39.93, 32.85);

    /** Polygon from local (east, north) metre offsets around {@code origin}. */
    static RestrictedZone zone(GeoPoint origin, double... en) {
        List<GeoPoint> v = new ArrayList<>();
        for (int i = 0; i < en.length; i += 2) {
            v.add(Geodesy.fromEastNorth(origin, en[i], en[i + 1]));
        }
        return new RestrictedZone("z", "Bölge-1", v, 0);
    }

    static GeoPoint at(GeoPoint origin, double e, double n) {
        return Geodesy.fromEastNorth(origin, e, n);
    }

    @Test
    void squareContainsCentreNotOutside() {
        RestrictedZone z = zone(ANKARA, 0, 0, 1000, 0, 1000, 1000, 0, 1000);
        assertTrue(z.contains(at(ANKARA, 500, 500)));
        assertTrue(z.contains(at(ANKARA, 1, 1)));
        assertTrue(z.contains(at(ANKARA, 999, 999)));
        assertFalse(z.contains(at(ANKARA, -1, 500)));
        assertFalse(z.contains(at(ANKARA, 1001, 500)));
        assertFalse(z.contains(at(ANKARA, 500, 1001)));
        assertFalse(z.contains(at(ANKARA, 500, -1)));
    }

    @Test
    void vertexOrderDoesNotMatter() {
        RestrictedZone cw = zone(ANKARA, 0, 0, 0, 1000, 1000, 1000, 1000, 0);
        assertTrue(cw.contains(at(ANKARA, 500, 500)));
        assertEquals(1e6, cw.areaM2(), 1e6 * 2e-4);
    }

    @Test
    void concaveUShape() {
        // U opening to the north: notch between x 300..700 above y 300.
        RestrictedZone u = zone(ANKARA, 0, 0, 1000, 0, 1000, 1000, 700, 1000, 700, 300, 300, 300, 300, 1000, 0, 1000);
        assertTrue(u.contains(at(ANKARA, 150, 800)));   // left arm
        assertTrue(u.contains(at(ANKARA, 850, 800)));   // right arm
        assertTrue(u.contains(at(ANKARA, 500, 150)));   // base
        assertFalse(u.contains(at(ANKARA, 500, 600)));  // inside the notch
        assertFalse(u.contains(at(ANKARA, 500, 999)));
        assertEquals(1e6 - 400 * 700, u.areaM2(), 720_000 * 2e-4);
    }

    @Test
    void rayThroughVertexCountsOnce() {
        // Diamond: rays from points at the height of the side vertices pass exactly through them.
        RestrictedZone d = zone(ANKARA, 0, -500, 500, 0, 0, 500, -500, 0);
        assertTrue(d.contains(at(ANKARA, 0, 0)));
        assertTrue(d.contains(at(ANKARA, -499, 0)));
        assertFalse(d.contains(at(ANKARA, -501, 0)));
        assertFalse(d.contains(at(ANKARA, 501, 0)));
        assertFalse(d.contains(at(ANKARA, 0, 501)));
    }

    @Test
    void rayAlongHorizontalEdge() {
        // Concave shape with horizontal edges at y = 0 and y = 500.
        RestrictedZone z = zone(ANKARA, 0, 0, 400, 0, 400, 500, 800, 500, 800, 1000, 0, 1000);
        assertTrue(z.contains(at(ANKARA, 200, 250)));
        assertFalse(z.contains(at(ANKARA, 600, 250)));
        assertTrue(z.contains(at(ANKARA, 600, 750)));
        assertFalse(z.contains(at(ANKARA, -100, 500)));
        assertFalse(z.contains(at(ANKARA, 900, 500)));
    }

    @Test
    void smallTriangleIsPrecise() {
        RestrictedZone t = zone(ANKARA, 0, 0, 20, 0, 0, 20);
        assertTrue(t.contains(at(ANKARA, 5, 5)));
        assertTrue(t.contains(at(ANKARA, 9.9, 9.9)));
        assertFalse(t.contains(at(ANKARA, 10.2, 10.2)));
        assertEquals(200, t.areaM2(), 0.01);
    }

    @Test
    void farAwayPointIsOutside() {
        RestrictedZone z = zone(ANKARA, 0, 0, 1000, 0, 1000, 1000, 0, 1000);
        assertFalse(z.contains(new GeoPoint(-39.93, -147.15)));
        assertFalse(z.contains(new GeoPoint(89.9, 0)));
    }

    @Test
    void worksAcrossAntimeridian() {
        GeoPoint origin = new GeoPoint(10, 179.995);
        RestrictedZone z = zone(origin, -2000, -1000, 2000, -1000, 2000, 1000, -2000, 1000);
        assertTrue(z.vertices().stream().anyMatch(v -> v.lon() < 0));
        assertTrue(z.contains(new GeoPoint(10, -179.999)));
        assertTrue(z.contains(new GeoPoint(10, 179.99)));
        assertFalse(z.contains(new GeoPoint(10, -179.9)));
        assertEquals(8e6, z.areaM2(), 8e6 * 1e-3);
        GeoPoint c = z.centroid();
        assertEquals(0, Geodesy.distanceMeters(c, origin), 1.0);
    }

    @Test
    void centroidOfRectangleAndLShape() {
        RestrictedZone r = zone(ANKARA, 0, 0, 2000, 0, 2000, 1000, 0, 1000);
        assertEquals(0, Geodesy.distanceMeters(r.centroid(), at(ANKARA, 1000, 500)), 0.5);
        // L of two unit squares 1000x1000: (0..2000 x 0..1000) + (0..1000 x 1000..2000)
        RestrictedZone l = zone(ANKARA, 0, 0, 2000, 0, 2000, 1000, 1000, 1000, 1000, 2000, 0, 2000);
        // centroid = average of (1000,500) weight 2 and (500,1500) weight 1 = (833.3, 833.3)
        assertEquals(0, Geodesy.distanceMeters(l.centroid(), at(ANKARA, 2500.0 / 3, 2500.0 / 3)), 0.5);
        assertEquals(3e6, l.areaM2(), 3e6 * 2e-4);
    }

    @Test
    void degenerateCentroidFallsBack() {
        RestrictedZone line = zone(ANKARA, 0, 0, 100, 0, 200, 0);
        assertEquals(0, line.areaM2(), 1e-6);
        assertEquals(0, Geodesy.distanceMeters(line.centroid(), at(ANKARA, 100, 0)), 0.01);
        assertFalse(line.contains(at(ANKARA, 100, 0)));
    }

    @Test
    void validation() {
        GeoPoint a = new GeoPoint(1, 1);
        assertThrows(IllegalArgumentException.class, () -> new RestrictedZone("z", "n", List.of(a, a), 0));
        assertThrows(IllegalArgumentException.class, () -> new RestrictedZone(" ", "n", List.of(a, a, a), 0));
        assertThrows(NullPointerException.class, () -> new RestrictedZone("z", null, List.of(a, a, a), 0));
    }

    @Test
    void verticesAreImmutable() {
        List<GeoPoint> v = new ArrayList<>(List.of(new GeoPoint(0, 0), new GeoPoint(0, 1), new GeoPoint(1, 0)));
        RestrictedZone z = new RestrictedZone("z", "n", v, 0);
        v.clear();
        assertEquals(3, z.vertices().size());
        assertThrows(UnsupportedOperationException.class, () -> z.vertices().clear());
        assertEquals("yeni", z.withName("yeni").name());
    }
}
