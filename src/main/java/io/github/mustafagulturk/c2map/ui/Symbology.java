package io.github.mustafagulturk.c2map.ui;

import io.github.mustafagulturk.c2map.track.Identity;
import io.github.mustafagulturk.c2map.track.TrackSnapshot;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/**
 * APP-6 / MIL-STD-2525 inspired track symbols: friend = cyan circle (air) or rectangle (surface),
 * hostile = red diamond, unknown = yellow quatrefoil. Symbols have a fixed screen size regardless of
 * zoom. Drawing methods use shared scratch buffers and must only be called on the JavaFX thread.
 */
public final class Symbology {

    /** Nominal symbol size in pixels. */
    public static final double SYMBOL_SIZE = 18.0;
    /** Ground speed above which a track is drawn with an air frame, m/s (about 100 kt). */
    public static final double AIR_SPEED_MPS = 50.0;
    public static final double MPS_TO_KNOTS = 3600.0 / 1852.0;

    private static final Color FRIEND = Color.rgb(0, 200, 255);
    private static final Color HOSTILE = Color.rgb(255, 48, 48);
    private static final Color UNKNOWN = Color.rgb(255, 222, 0);
    private static final Color FRIEND_FILL = Color.rgb(0, 200, 255, 0.28);
    private static final Color HOSTILE_FILL = Color.rgb(255, 48, 48, 0.28);
    private static final Color UNKNOWN_FILL = Color.rgb(255, 222, 0, 0.28);

    /** Quatrefoil outline for a unit half-size (extent [-1, 1]), clockwise in screen space. */
    private static final double[] CLOVER_X;
    private static final double[] CLOVER_Y;
    private static final int CLOVER_STEPS = 8;

    static {
        // Four half-circle lobes of radius d centred at (0,-d), (d,0), (0,d), (-d,0) with d = 0.5:
        // the union reaches +-1 on the axes and is notched on the diagonals.
        double d = 0.5;
        double[][] centres = {{0, -d}, {d, 0}, {0, d}, {-d, 0}};
        double[] startAngles = {Math.PI, -Math.PI / 2, 0, Math.PI / 2};
        int n = 4 * CLOVER_STEPS;
        CLOVER_X = new double[n];
        CLOVER_Y = new double[n];
        int k = 0;
        for (int lobe = 0; lobe < 4; lobe++) {
            for (int i = 0; i < CLOVER_STEPS; i++) {
                double a = startAngles[lobe] + Math.PI * i / CLOVER_STEPS;
                CLOVER_X[k] = centres[lobe][0] + d * Math.cos(a);
                CLOVER_Y[k] = centres[lobe][1] + d * Math.sin(a);
                k++;
            }
        }
    }

    private static final double[] XS = new double[CLOVER_X.length];
    private static final double[] YS = new double[CLOVER_X.length];

    private Symbology() {
    }

    public static Color frameColor(Identity identity) {
        return switch (identity) {
            case FRIEND -> FRIEND;
            case HOSTILE -> HOSTILE;
            case UNKNOWN -> UNKNOWN;
        };
    }

    public static Color fillColor(Identity identity) {
        return switch (identity) {
            case FRIEND -> FRIEND_FILL;
            case HOSTILE -> HOSTILE_FILL;
            case UNKNOWN -> UNKNOWN_FILL;
        };
    }

    /** Air or surface frame; the track messages carry no domain, so it is inferred from speed. */
    public static boolean isAir(TrackSnapshot track) {
        return track.speedMps() >= AIR_SPEED_MPS;
    }

    /**
     * Draws the frame of one symbol centred on {@code (x, y)}. The caller sets fill, stroke and
     * line width (see {@link #fillColor} and {@link #frameColor}), so many symbols of one identity
     * can be drawn without state changes.
     */
    public static void drawFrame(GraphicsContext g, Identity identity, boolean air, double x, double y,
            double size) {
        drawFrame(g, identity, air, x, y, size, true);
    }

    /** As {@link #drawFrame(GraphicsContext, Identity, boolean, double, double, double)}, optionally without fill. */
    public static void drawFrame(GraphicsContext g, Identity identity, boolean air, double x, double y,
            double size, boolean fill) {
        double h = size / 2;
        switch (identity) {
            case FRIEND -> {
                if (air) {
                    if (fill) {
                        g.fillOval(x - h, y - h, size, size);
                    }
                    g.strokeOval(x - h, y - h, size, size);
                } else {
                    double hh = h * 0.75;
                    if (fill) {
                        g.fillRect(x - h, y - hh, size, 2 * hh);
                    }
                    g.strokeRect(x - h, y - hh, size, 2 * hh);
                }
            }
            case HOSTILE -> {
                double r = h * 1.2;
                XS[0] = x;
                YS[0] = y - r;
                XS[1] = x + r;
                YS[1] = y;
                XS[2] = x;
                YS[2] = y + r;
                XS[3] = x - r;
                YS[3] = y;
                if (fill) {
                    g.fillPolygon(XS, YS, 4);
                }
                g.strokePolygon(XS, YS, 4);
            }
            case UNKNOWN -> {
                double r = h * 1.1;
                for (int i = 0; i < CLOVER_X.length; i++) {
                    XS[i] = x + CLOVER_X[i] * r;
                    YS[i] = y + CLOVER_Y[i] * r;
                }
                if (fill) {
                    g.fillPolygon(XS, YS, CLOVER_X.length);
                }
                g.strokePolygon(XS, YS, CLOVER_X.length);
            }
        }
    }

    /** Convenience: sets the identity colours and draws one symbol. */
    public static void drawSymbol(GraphicsContext g, Identity identity, boolean air, double x, double y,
            double size) {
        g.setFill(fillColor(identity));
        g.setStroke(frameColor(identity));
        g.setLineWidth(2);
        drawFrame(g, identity, air, x, y, size);
    }

    public static double knots(double mps) {
        return mps * MPS_TO_KNOTS;
    }

    /** Track label, e.g. {@code "R-12  420kt 087°"}. */
    public static String label(TrackSnapshot track) {
        StringBuilder sb = new StringBuilder(20);
        sb.append(track.key()).append("  ").append(Math.round(knots(track.speedMps()))).append("kt ");
        long course = Math.round(track.courseDeg()) % 360;
        if (course < 100) {
            sb.append('0');
        }
        if (course < 10) {
            sb.append('0');
        }
        return sb.append(course).append('°').toString();
    }
}
