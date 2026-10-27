package io.github.mustafagulturk.c2map.map;

import java.util.Locale;

/** Metric scale bar arithmetic: round lengths (1, 2, 5 x 10^n metres) and their labels. */
public final class ScaleBar {

    private ScaleBar() {
    }

    /** Largest length of the form 1, 2 or 5 x 10^n that does not exceed {@code maxMeters}. */
    public static double niceLength(double maxMeters) {
        if (!(maxMeters > 0) || Double.isInfinite(maxMeters)) {
            throw new IllegalArgumentException("maxMeters must be positive: " + maxMeters);
        }
        double pow = Math.pow(10, Math.floor(Math.log10(maxMeters)));
        // Guard against log10 rounding just below an exact power of ten.
        if (pow * 10 <= maxMeters) {
            pow *= 10;
        }
        double f = maxMeters / pow;
        double nice = f >= 5 ? 5 : f >= 2 ? 2 : 1;
        return nice * pow;
    }

    /** "500 m", "2 km", "0,5 m" style label (Turkish decimal comma). */
    public static String label(double meters) {
        if (meters >= 1000) {
            return format(meters / 1000) + " km";
        }
        return format(meters) + " m";
    }

    private static String format(double v) {
        if (v == Math.rint(v)) {
            return Long.toString((long) v);
        }
        String s = String.format(Locale.ROOT, "%.3f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
        return s.replace('.', ',');
    }
}
