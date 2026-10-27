package io.github.mustafagulturk.c2map.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class GeodesyTest {

    private static final GeoPoint IZMIR = new GeoPoint(38.42, 27.14);
    private static final GeoPoint ANKARA = new GeoPoint(39.93, 32.85);

    @Test
    void distanceIzmirAnkara() {
        // Reference: ~518 km great-circle distance.
        assertEquals(518_000, Geodesy.distanceMeters(IZMIR, ANKARA), 3_000);
        assertEquals(0, Geodesy.distanceMeters(IZMIR, IZMIR), 1e-6);
    }

    @Test
    void bearingsOnCardinalDirections() {
        GeoPoint o = new GeoPoint(0, 0);
        assertEquals(0, Geodesy.initialBearingDeg(o, new GeoPoint(1, 0)), 1e-9);
        assertEquals(90, Geodesy.initialBearingDeg(o, new GeoPoint(0, 1)), 1e-9);
        assertEquals(180, Geodesy.initialBearingDeg(o, new GeoPoint(-1, 0)), 1e-9);
        assertEquals(270, Geodesy.initialBearingDeg(o, new GeoPoint(0, -1)), 1e-9);
    }

    @Test
    void destinationIsInverseOfDistanceAndBearing() {
        for (double bearing = 0; bearing < 360; bearing += 37) {
            GeoPoint p = Geodesy.destination(IZMIR, bearing, 25_000);
            assertEquals(25_000, Geodesy.distanceMeters(IZMIR, p), 0.01);
            double diff = Geodesy.normalizeDeg(Geodesy.initialBearingDeg(IZMIR, p) - bearing + 180) - 180;
            assertEquals(0, diff, 1e-6);
        }
    }

    @Test
    void eastNorthRoundTripAndAgreementWithGreatCircle() {
        GeoPoint p = Geodesy.fromEastNorth(IZMIR, 30_000, -20_000);
        double[] en = Geodesy.toEastNorth(IZMIR, p);
        assertEquals(30_000, en[0], 1e-6);
        assertEquals(-20_000, en[1], 1e-6);
        assertEquals(Math.hypot(30_000, 20_000), Geodesy.distanceMeters(IZMIR, p), 50);
    }

    @Test
    void normalisesAngles() {
        assertEquals(350, Geodesy.normalizeDeg(-10), 1e-12);
        assertEquals(0, Geodesy.normalizeDeg(720), 1e-12);
        assertEquals(-170, Geodesy.normalizeLon(190), 1e-12);
        assertEquals(179, Geodesy.normalizeLon(-181), 1e-12);
    }

    @Test
    void crossesAntimeridian() {
        GeoPoint p = Geodesy.destination(new GeoPoint(0, 179.9), 90, 50_000);
        assertEquals(-179.65, p.lon(), 0.01);
    }

    @Test
    void rejectsInvalidPositions() {
        assertThrows(IllegalArgumentException.class, () -> new GeoPoint(91, 0));
        assertThrows(IllegalArgumentException.class, () -> new GeoPoint(Double.NaN, 0));
    }
}
