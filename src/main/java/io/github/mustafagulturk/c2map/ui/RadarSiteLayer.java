package io.github.mustafagulturk.c2map.ui;

import io.github.mustafagulturk.c2map.geo.GeoPoint;
import io.github.mustafagulturk.c2map.geo.Geodesy;
import io.github.mustafagulturk.c2map.map.MapLayer;
import io.github.mustafagulturk.c2map.map.MapViewport;
import io.github.mustafagulturk.c2map.track.RadarSite;
import java.util.Objects;
import javafx.geometry.Point2D;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/** Radar site marker with faint range rings every 10 km, the coverage limit and a north tick. */
public final class RadarSiteLayer implements MapLayer {

    public static final double RING_STEP_M = 10_000.0;

    private static final Color RING = Color.rgb(120, 230, 140, 0.28);
    private static final Color LIMIT = Color.rgb(120, 230, 140, 0.55);
    private static final Color TEXT = Color.rgb(150, 235, 165, 0.75);
    private static final Color SITE = Color.rgb(150, 235, 165, 0.95);

    private final RadarSite site;
    private Font font;

    public RadarSiteLayer(RadarSite site) {
        this.site = Objects.requireNonNull(site, "site");
    }

    public RadarSite site() {
        return site;
    }

    @Override
    public void draw(GraphicsContext g, MapViewport viewport, long nowMs) {
        Point2D c = viewport.toScreen(site.position());
        double cx = c.getX();
        double cy = c.getY();
        double maxR = radiusPx(viewport, cy, site.maxRangeM());
        // Entirely off-screen?
        if (cx + maxR < 0 || cy + maxR < 0 || cx - maxR > viewport.width() || cy - maxR > viewport.height()) {
            return;
        }
        if (font == null) {
            font = Font.font("Monospaced", 10);
        }
        g.save();
        g.setLineDashes((double[]) null);
        g.setFont(font);
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.BOTTOM);
        double stepPx = radiusPx(viewport, cy, RING_STEP_M);
        boolean ringLabels = stepPx >= 30;
        if (stepPx >= 6) {
            g.setLineWidth(1);
            for (double r = RING_STEP_M; r < site.maxRangeM() - 1; r += RING_STEP_M) {
                double rp = radiusPx(viewport, cy, r);
                g.setStroke(RING);
                g.strokeOval(cx - rp, cy - rp, 2 * rp, 2 * rp);
                if (ringLabels) {
                    g.setFill(TEXT);
                    g.fillText(Math.round(r / 1000) + " km", cx, cy - rp - 1);
                }
            }
        }
        g.setStroke(LIMIT);
        g.setLineWidth(1.2);
        g.strokeOval(cx - maxR, cy - maxR, 2 * maxR, 2 * maxR);
        if (ringLabels) {
            g.setFill(TEXT);
            g.fillText(Math.round(site.maxRangeM() / 1000) + " km", cx, cy - maxR - 1);
        }

        // North tick on the coverage limit and a "K" (kuzey) mark.
        g.setStroke(LIMIT);
        g.setLineWidth(2);
        g.strokeLine(cx, cy - maxR, cx, cy - maxR - 10);
        g.setFill(SITE);
        g.fillText("K", cx, cy - maxR - (ringLabels ? 14 : 11));

        // Site marker: small square with a centre dot.
        g.setStroke(SITE);
        g.setLineWidth(1.5);
        g.strokeRect(cx - 5, cy - 5, 10, 10);
        g.fillOval(cx - 1.5, cy - 1.5, 3, 3);
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.TOP);
        g.fillText("RADAR", cx + 8, cy + 6);
        g.restore();
    }

    /** Screen radius of a ground distance, measured northwards from the site (exact in Mercator). */
    private double radiusPx(MapViewport viewport, double cy, double meters) {
        GeoPoint north = Geodesy.destination(site.position(), 0, meters);
        return Math.abs(cy - viewport.toScreen(north).getY());
    }
}
