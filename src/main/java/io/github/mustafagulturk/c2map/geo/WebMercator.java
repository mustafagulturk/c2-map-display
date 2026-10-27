package io.github.mustafagulturk.c2map.geo;

/**
 * Spherical Web Mercator (EPSG:3857) helpers in "world pixel" space, as used by slippy-map tiles.
 *
 * <p>At zoom {@code z} the whole world is a square of {@code 256 * 2^z} pixels; x grows eastwards from
 * longitude -180, y grows southwards from latitude {@link #MAX_LAT}. Zoom may be fractional.
 */
public final class WebMercator {

    /** Edge length of one tile in pixels. */
    public static final int TILE_SIZE = 256;

    /** Latitude limit of the square Mercator world, degrees. */
    public static final double MAX_LAT = 85.0511287798066;

    /** Equatorial radius used by EPSG:3857, metres. */
    public static final double EARTH_RADIUS_M = 6_378_137.0;

    private WebMercator() {
    }

    /** Size of the world square at {@code zoom}, in pixels. */
    public static double worldSize(double zoom) {
        return TILE_SIZE * Math.pow(2.0, zoom);
    }

    /** Clamps a latitude to the Mercator limit of ±{@link #MAX_LAT}. */
    public static double clampLat(double lat) {
        return Math.max(-MAX_LAT, Math.min(MAX_LAT, lat));
    }

    /** World pixel x of a longitude (not wrapped: lon 180 maps to the world size). */
    public static double lonToX(double lon, double zoom) {
        return (lon + 180.0) / 360.0 * worldSize(zoom);
    }

    /** World pixel y of a latitude, clamped to the Mercator limit. */
    public static double latToY(double lat, double zoom) {
        double phi = Math.toRadians(clampLat(lat));
        double merc = Math.log(Math.tan(Math.PI / 4 + phi / 2));
        return (1.0 - merc / Math.PI) / 2.0 * worldSize(zoom);
    }

    /** Longitude of a world pixel x; the result is not wrapped into [-180, 180). */
    public static double xToLon(double x, double zoom) {
        return x / worldSize(zoom) * 360.0 - 180.0;
    }

    /** Latitude of a world pixel y; y outside the world square is clamped. */
    public static double yToLat(double y, double zoom) {
        double size = worldSize(zoom);
        double t = Math.max(0.0, Math.min(size, y)) / size;
        double merc = Math.PI * (1.0 - 2.0 * t);
        return Math.toDegrees(Math.atan(Math.sinh(merc)));
    }

    /** World pixel {x, y} of a point. */
    public static double[] toPixel(GeoPoint p, double zoom) {
        return new double[] {lonToX(p.lon(), zoom), latToY(p.lat(), zoom)};
    }

    /** Point at world pixel (x, y); the longitude is normalised to [-180, 180). */
    public static GeoPoint fromPixel(double x, double y, double zoom) {
        return new GeoPoint(yToLat(y, zoom), Geodesy.normalizeLon(xToLon(x, zoom)));
    }

    /** Column of the tile containing {@code lon} at integer zoom {@code z}, in [0, 2^z). */
    public static int tileX(double lon, int z) {
        int n = 1 << z;
        int x = (int) Math.floor((Geodesy.normalizeLon(lon) + 180.0) / 360.0 * n);
        return Math.max(0, Math.min(n - 1, x));
    }

    /** Row of the tile containing {@code lat} at integer zoom {@code z}, in [0, 2^z). */
    public static int tileY(double lat, int z) {
        int n = 1 << z;
        int y = (int) Math.floor(latToY(lat, z) / TILE_SIZE);
        return Math.max(0, Math.min(n - 1, y));
    }

    /** Ground metres covered by one pixel at {@code lat} and {@code zoom}. */
    public static double metersPerPixel(double lat, double zoom) {
        return Math.cos(Math.toRadians(clampLat(lat))) * 2 * Math.PI * EARTH_RADIUS_M / worldSize(zoom);
    }
}
