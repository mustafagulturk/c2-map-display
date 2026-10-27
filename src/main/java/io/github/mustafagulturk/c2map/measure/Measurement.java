package io.github.mustafagulturk.c2map.measure;

import io.github.mustafagulturk.c2map.geo.GeoPoint;
import io.github.mustafagulturk.c2map.geo.Geodesy;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Objects;

/**
 * Range and bearing between two points, with operator text in Turkish number style
 * (decimal comma, e.g. {@code "12,4 km · 6,7 NM · 087°"}).
 *
 * @param from start point
 * @param to   end point
 */
public record Measurement(GeoPoint from, GeoPoint to) {

    /** One international nautical mile in metres. */
    public static final double METERS_PER_NM = 1852.0;

    public Measurement {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }

    /** Great-circle distance in metres. */
    public double distanceM() {
        return Geodesy.distanceMeters(from, to);
    }

    public double distanceNm() {
        return distanceM() / METERS_PER_NM;
    }

    /** Initial true bearing from {@code from} to {@code to}, degrees [0, 360). */
    public double bearingDeg() {
        return Geodesy.initialBearingDeg(from, to);
    }

    /** True bearing from {@code to} back to {@code from}, degrees [0, 360). */
    public double reverseBearingDeg() {
        return Geodesy.initialBearingDeg(to, from);
    }

    /** {@code "12,4 km · 6,7 NM · 087°"}. */
    public String label() {
        return formatKm(distanceM()) + " · " + formatNm(distanceM()) + " · " + formatBearing(bearingDeg());
    }

    /** {@code "12,4 km · 6,7 NM"}. */
    public String distanceText() {
        return formatKm(distanceM()) + " · " + formatNm(distanceM());
    }

    /** {@code "Kerteriz 087° · Ters 267°"}. */
    public String bearingText() {
        return "Kerteriz " + formatBearing(bearingDeg()) + " · Ters " + formatBearing(reverseBearingDeg());
    }

    /**
     * Metric distance: whole metres below 1 km ({@code "850 m"}), one decimal below 100 km
     * ({@code "12,4 km"}), whole kilometres above ({@code "1.234 km"}).
     */
    public static String formatKm(double meters) {
        if (Math.round(meters) < 1000) {
            return format("0", meters) + " m";
        }
        double km = meters / 1000.0;
        return (km < 99.95 ? format("0.0", km) : format("#,##0", km)) + " km";
    }

    /** Nautical miles: one decimal below 100 NM ({@code "4,0 NM"}), whole numbers above. */
    public static String formatNm(double meters) {
        double nm = meters / METERS_PER_NM;
        return (nm < 99.95 ? format("0.0", nm) : format("#,##0", nm)) + " NM";
    }

    /** Three-digit whole degrees, {@code "087°"}; 359.6 rounds to {@code "000°"}. */
    public static String formatBearing(double deg) {
        long d = Math.round(Geodesy.normalizeDeg(deg)) % 360;
        return String.format(java.util.Locale.ROOT, "%03d°", d);
    }

    private static String format(String pattern, double value) {
        DecimalFormatSymbols sym = DecimalFormatSymbols.getInstance(java.util.Locale.ROOT);
        sym.setDecimalSeparator(',');
        sym.setGroupingSeparator('.');
        DecimalFormat f = new DecimalFormat(pattern, sym);
        f.setRoundingMode(java.math.RoundingMode.HALF_UP);
        return f.format(value);
    }
}
