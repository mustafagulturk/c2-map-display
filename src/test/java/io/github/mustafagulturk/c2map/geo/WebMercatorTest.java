package io.github.mustafagulturk.c2map.geo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WebMercatorTest {

    @Test
    void originAndCornersAtZoomZero() {
        assertEquals(128.0, WebMercator.lonToX(0, 0), 1e-9);
        assertEquals(128.0, WebMercator.latToY(0, 0), 1e-9);
        assertEquals(0.0, WebMercator.lonToX(-180, 0), 1e-9);
        assertEquals(0.0, WebMercator.latToY(WebMercator.MAX_LAT, 0), 1e-6);
        assertEquals(256.0, WebMercator.latToY(-WebMercator.MAX_LAT, 0), 1e-6);
    }

    @Test
    void latitudeIsClamped() {
        assertEquals(WebMercator.latToY(WebMercator.MAX_LAT, 3), WebMercator.latToY(89.9, 3), 1e-9);
        assertEquals(WebMercator.MAX_LAT, WebMercator.yToLat(-500, 3), 1e-9);
        assertEquals(-WebMercator.MAX_LAT, WebMercator.yToLat(1e9, 3), 1e-9);
        assertEquals(-WebMercator.MAX_LAT, WebMercator.clampLat(-90), 0);
    }

    @Test
    void roundTripAtFractionalZoom() {
        double[][] pts = {{38.42, 27.14}, {-33.86, 151.21}, {64.1, -21.9}, {0, 0}, {85, -179.99}};
        for (double zoom : new double[] {0, 2.5, 9.75, 17.3}) {
            for (double[] p : pts) {
                GeoPoint g = new GeoPoint(p[0], p[1]);
                double[] px = WebMercator.toPixel(g, zoom);
                GeoPoint back = WebMercator.fromPixel(px[0], px[1], zoom);
                assertEquals(g.lat(), back.lat(), 1e-9);
                assertEquals(g.lon(), back.lon(), 1e-9);
            }
        }
    }

    @Test
    void fractionalZoomScalesLinearly() {
        double x9 = WebMercator.lonToX(27.14, 9);
        assertEquals(x9 * Math.sqrt(2), WebMercator.lonToX(27.14, 9.5), 1e-6);
    }

    @Test
    void knownTileNumbers() {
        // Izmir city centre is in OSM tile 9/294/196 and 11/1178/786.
        assertArrayEquals(new int[] {294, 196}, tile(38.42, 27.14, 9));
        assertArrayEquals(new int[] {1178, 786}, tile(38.42, 27.14, 11));
        // Berlin (52.52, 13.405) at z10 -> 550/335 (OSM wiki example).
        assertArrayEquals(new int[] {550, 335}, tile(52.52, 13.405, 10));
        assertArrayEquals(new int[] {0, 0}, tile(10, 10, 0));
        // Edges stay inside the grid.
        assertArrayEquals(new int[] {7, 7}, tile(-89, 179.999, 3));
        assertArrayEquals(new int[] {0, 4}, tile(0, 180, 3)); // lon 180 wraps to -180
        assertArrayEquals(new int[] {0, 0}, tile(89, -180, 3));
    }

    @Test
    void metersPerPixelMatchesOsmTable() {
        // OSM wiki: 156543.03 m/px at z0 on the equator, 4891.97 at z5.
        assertEquals(156543.03, WebMercator.metersPerPixel(0, 0), 0.01);
        assertEquals(4891.97, WebMercator.metersPerPixel(0, 5), 0.01);
        assertEquals(4891.97 * Math.cos(Math.toRadians(60)), WebMercator.metersPerPixel(60, 5), 0.01);
    }

    private static int[] tile(double lat, double lon, int z) {
        return new int[] {WebMercator.tileX(lon, z), WebMercator.tileY(lat, z)};
    }
}
