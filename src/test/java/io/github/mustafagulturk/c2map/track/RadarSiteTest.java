package io.github.mustafagulturk.c2map.track;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.mustafagulturk.c2map.geo.GeoPoint;
import io.github.mustafagulturk.c2map.geo.Geodesy;
import org.junit.jupiter.api.Test;

class RadarSiteTest {

    private final RadarSite site = new RadarSite(new GeoPoint(38.42, 27.14), 40_000);

    @Test
    void polarIsClockwiseFromNorth() {
        GeoPoint north = site.polar(10_000, 0);
        assertEquals(10_000, Geodesy.distanceMeters(site.position(), north), 1);
        double b = Geodesy.initialBearingDeg(site.position(), north);
        assertEquals(0, Math.min(b, 360 - b), 0.1);

        GeoPoint east = site.polar(10_000, Math.PI / 2);
        assertEquals(90, Geodesy.initialBearingDeg(site.position(), east), 0.1);
        assertEquals(10_000, Geodesy.distanceMeters(site.position(), east), 5);

        GeoPoint sw = site.polar(20_000, Math.toRadians(225));
        assertEquals(225, Geodesy.initialBearingDeg(site.position(), sw), 0.2);
    }

    @Test
    void eastNorthRoundTrip() {
        GeoPoint p = site.fromEastNorth(-12_345, 6_789);
        double[] en = site.toEastNorth(p);
        assertEquals(-12_345, en[0], 1e-6);
        assertEquals(6_789, en[1], 1e-6);
    }

    @Test
    void validates() {
        assertThrows(IllegalArgumentException.class, () -> new RadarSite(new GeoPoint(0, 0), 0));
        assertThrows(NullPointerException.class, () -> new RadarSite(null, 1));
    }
}
