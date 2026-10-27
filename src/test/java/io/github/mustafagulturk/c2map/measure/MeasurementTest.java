package io.github.mustafagulturk.c2map.measure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.mustafagulturk.c2map.geo.GeoPoint;
import io.github.mustafagulturk.c2map.geo.Geodesy;
import org.junit.jupiter.api.Test;

class MeasurementTest {

    private static final GeoPoint ORIGIN = new GeoPoint(39.93, 32.85);

    @Test
    void distanceAndBearings() {
        GeoPoint to = Geodesy.destination(ORIGIN, 87, 12_400);
        Measurement m = new Measurement(ORIGIN, to);
        assertEquals(12_400, m.distanceM(), 0.01);
        assertEquals(12_400 / 1852.0, m.distanceNm(), 1e-5);
        assertEquals(87, m.bearingDeg(), 1e-6);
        // On a sphere the back bearing differs slightly from bearing + 180 away from the equator.
        assertEquals(267, m.reverseBearingDeg(), 0.2);
        assertEquals("12,4 km · 6,7 NM · 087°", m.label());
        assertEquals("12,4 km · 6,7 NM", m.distanceText());
        assertEquals("Kerteriz 087° · Ters 267°", m.bearingText());
    }

    @Test
    void cardinalBearings() {
        assertEquals(0, angleDiff(0, new Measurement(ORIGIN, Geodesy.destination(ORIGIN, 0, 1000)).bearingDeg()), 1e-6);
        assertEquals(180, new Measurement(ORIGIN, Geodesy.destination(ORIGIN, 0, 1000)).reverseBearingDeg(), 1e-6);
        assertEquals(270, new Measurement(ORIGIN, Geodesy.destination(ORIGIN, 270, 1000)).bearingDeg(), 1e-3);
    }

    private static double angleDiff(double a, double b) {
        double d = Math.abs(a - b) % 360;
        return Math.min(d, 360 - d);
    }

    @Test
    void kmFormatting() {
        assertEquals("0 m", Measurement.formatKm(0));
        assertEquals("850 m", Measurement.formatKm(849.6));
        assertEquals("1,0 km", Measurement.formatKm(999.6));
        assertEquals("1,5 km", Measurement.formatKm(1460));
        assertEquals("12,4 km", Measurement.formatKm(12_440));
        assertEquals("99,9 km", Measurement.formatKm(99_940));
        assertEquals("100 km", Measurement.formatKm(99_960));
        assertEquals("1.234 km", Measurement.formatKm(1_234_400));
    }

    @Test
    void nmFormatting() {
        assertEquals("4,0 NM", Measurement.formatNm(4 * 1852));
        assertEquals("0,5 NM", Measurement.formatNm(926));
        assertEquals("150 NM", Measurement.formatNm(150 * 1852));
        assertEquals("1.000 NM", Measurement.formatNm(1000 * 1852));
    }

    @Test
    void bearingFormatting() {
        assertEquals("000°", Measurement.formatBearing(0));
        assertEquals("005°", Measurement.formatBearing(4.6));
        assertEquals("087°", Measurement.formatBearing(87.4));
        assertEquals("000°", Measurement.formatBearing(359.6));
        assertEquals("359°", Measurement.formatBearing(359.4));
        assertEquals("270°", Measurement.formatBearing(-90));
    }

    @Test
    void zeroLength() {
        Measurement m = new Measurement(ORIGIN, ORIGIN);
        assertEquals(0, m.distanceM());
        assertEquals("0 m · 0,0 NM · 000°", m.label());
    }
}
