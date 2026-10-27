package io.github.mustafagulturk.c2map.geo;

/**
 * Spherical-earth geodesy helpers. Accuracy is about 0.5 % against WGS-84, which is well below
 * radar measurement noise and plenty for an operator display.
 */
public final class Geodesy {

    /** Mean earth radius (IUGG), metres. */
    public static final double EARTH_RADIUS_M = 6_371_008.8;

    private Geodesy() {
    }

    /** Great-circle distance in metres (haversine). */
    public static double distanceMeters(GeoPoint a, GeoPoint b) {
        double phi1 = Math.toRadians(a.lat());
        double phi2 = Math.toRadians(b.lat());
        double dPhi = phi2 - phi1;
        double dLambda = Math.toRadians(b.lon() - a.lon());
        double h = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }

    /** Initial great-circle bearing from {@code a} to {@code b}, degrees clockwise from true north, [0, 360). */
    public static double initialBearingDeg(GeoPoint a, GeoPoint b) {
        double phi1 = Math.toRadians(a.lat());
        double phi2 = Math.toRadians(b.lat());
        double dLambda = Math.toRadians(b.lon() - a.lon());
        double y = Math.sin(dLambda) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLambda);
        return normalizeDeg(Math.toDegrees(Math.atan2(y, x)));
    }

    /** Point reached by travelling {@code distanceM} metres from {@code start} on initial bearing {@code bearingDeg}. */
    public static GeoPoint destination(GeoPoint start, double bearingDeg, double distanceM) {
        double delta = distanceM / EARTH_RADIUS_M;
        double theta = Math.toRadians(bearingDeg);
        double phi1 = Math.toRadians(start.lat());
        double lambda1 = Math.toRadians(start.lon());
        double sinPhi2 = Math.sin(phi1) * Math.cos(delta) + Math.cos(phi1) * Math.sin(delta) * Math.cos(theta);
        double phi2 = Math.asin(Math.max(-1.0, Math.min(1.0, sinPhi2)));
        double lambda2 = lambda1 + Math.atan2(
                Math.sin(theta) * Math.sin(delta) * Math.cos(phi1),
                Math.cos(delta) - Math.sin(phi1) * sinPhi2);
        return new GeoPoint(Math.toDegrees(phi2), normalizeLon(Math.toDegrees(lambda2)));
    }

    /**
     * Local tangent-plane (east, north) offset of {@code p} from {@code origin} in metres.
     * Equirectangular approximation, accurate to centimetres within a radar's ~100 km coverage.
     */
    public static double[] toEastNorth(GeoPoint origin, GeoPoint p) {
        double east = Math.toRadians(normalizeLon(p.lon() - origin.lon())) * EARTH_RADIUS_M
                * Math.cos(Math.toRadians(origin.lat()));
        double north = Math.toRadians(p.lat() - origin.lat()) * EARTH_RADIUS_M;
        return new double[] {east, north};
    }

    /** Inverse of {@link #toEastNorth}. */
    public static GeoPoint fromEastNorth(GeoPoint origin, double eastM, double northM) {
        double lat = origin.lat() + Math.toDegrees(northM / EARTH_RADIUS_M);
        double lon = origin.lon()
                + Math.toDegrees(eastM / (EARTH_RADIUS_M * Math.cos(Math.toRadians(origin.lat()))));
        return new GeoPoint(lat, normalizeLon(lon));
    }

    public static double normalizeDeg(double deg) {
        double d = deg % 360.0;
        if (d < 0) {
            d += 360.0;
        }
        return d >= 360.0 ? 0.0 : d;
    }

    public static double normalizeLon(double lon) {
        double l = (lon + 180.0) % 360.0;
        if (l < 0) {
            l += 360.0;
        }
        return l - 180.0;
    }
}
