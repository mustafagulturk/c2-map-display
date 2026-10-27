package io.github.mustafagulturk.c2map.geo;

/** A WGS-84 position in decimal degrees. */
public record GeoPoint(double lat, double lon) {

    public GeoPoint {
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || lat < -90 || lat > 90) {
            throw new IllegalArgumentException("invalid position " + lat + ", " + lon);
        }
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT, "%.5f, %.5f", lat, lon);
    }
}
