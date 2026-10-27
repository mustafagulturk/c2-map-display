package io.github.mustafagulturk.c2map.track;

import io.github.mustafagulturk.c2map.geo.GeoPoint;
import io.github.mustafagulturk.c2map.geo.Geodesy;
import java.util.Objects;

/**
 * Location and coverage of the radar. The tracker works in the local east/north plane centred on
 * the site; this record converts between that plane and geographic positions.
 *
 * @param position  antenna position
 * @param maxRangeM instrumented range, metres
 */
public record RadarSite(GeoPoint position, double maxRangeM) {

    /** Default demo site: İzmir, 40 km like radar_simulator. */
    public static final RadarSite DEFAULT = new RadarSite(new GeoPoint(38.42, 27.14), 40_000.0);

    public RadarSite {
        Objects.requireNonNull(position, "position");
        if (!(maxRangeM > 0) || !Double.isFinite(maxRangeM)) {
            throw new IllegalArgumentException("invalid max range " + maxRangeM);
        }
    }

    /** Position of a plot at {@code rangeM} and {@code azimuthRad} (clockwise from north). */
    public GeoPoint polar(double rangeM, double azimuthRad) {
        return Geodesy.fromEastNorth(position, rangeM * Math.sin(azimuthRad), rangeM * Math.cos(azimuthRad));
    }

    public GeoPoint fromEastNorth(double eastM, double northM) {
        return Geodesy.fromEastNorth(position, eastM, northM);
    }

    /** {@code {east, north}} of {@code p} relative to the site, metres. */
    public double[] toEastNorth(GeoPoint p) {
        return Geodesy.toEastNorth(position, p);
    }
}
