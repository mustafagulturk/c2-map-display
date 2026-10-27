package io.github.mustafagulturk.c2map.ui;

import io.github.mustafagulturk.c2map.geo.GeoPoint;
import io.github.mustafagulturk.c2map.map.MapLayer;
import io.github.mustafagulturk.c2map.map.MapViewport;
import io.github.mustafagulturk.c2map.zone.RestrictedZone;
import io.github.mustafagulturk.c2map.zone.ZoneMonitor;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javafx.geometry.Point2D;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

/**
 * Draws restricted zones as translucent red polygons with a red border and name label. A zone
 * with a track inside pulses (about 1 Hz, driven by {@code nowMs}, so the map must be repainted
 * continuously while {@link ZoneMonitor#intrudedZoneIds()} is non-empty). Also draws the
 * {@link ZoneEditor} draft with its rubber band, if an editor is given.
 */
public final class ZoneLayer implements MapLayer {

    private static final Color BORDER = Color.web("#ff3b30");
    private static final Color DRAFT = Color.web("#ffcc00");
    private static final Font LABEL_FONT = Font.font("System", FontWeight.BOLD, 12);
    private static final double PULSE_PERIOD_MS = 1000;

    private final ZoneMonitor monitor;
    private final ZoneEditor editor;

    /** @param editor drawing tool whose draft is shown, or null */
    public ZoneLayer(ZoneMonitor monitor, ZoneEditor editor) {
        this.monitor = Objects.requireNonNull(monitor);
        this.editor = editor;
    }

    public ZoneLayer(ZoneMonitor monitor) {
        this(monitor, null);
    }

    /** Topmost zone under the screen point, if any. */
    public Optional<RestrictedZone> zoneAt(MapViewport viewport, double x, double y) {
        return monitor.zoneAt(viewport.toGeo(x, y));
    }

    @Override
    public void draw(GraphicsContext g, MapViewport viewport, long nowMs) {
        List<RestrictedZone> zones = monitor.zones();
        Set<String> intruded = zones.isEmpty() ? Set.of() : monitor.intrudedZoneIds();
        double phase = (Math.floorMod(nowMs, (long) PULSE_PERIOD_MS)) / PULSE_PERIOD_MS;
        double pulse = 0.5 + 0.5 * Math.cos(2 * Math.PI * phase); // 1 -> 0 -> 1
        g.save();
        try {
            for (RestrictedZone z : zones) {
                drawZone(g, viewport, z, intruded.contains(z.id()), pulse);
            }
            if (editor != null) {
                drawDraft(g, viewport, editor.draft(), editor.cursor());
            }
        } finally {
            g.restore();
        }
    }

    private static void drawZone(GraphicsContext g, MapViewport vp, RestrictedZone z, boolean alarm, double pulse) {
        List<GeoPoint> verts = z.vertices();
        double[] xs = new double[verts.size()];
        double[] ys = new double[verts.size()];
        for (int i = 0; i < xs.length; i++) {
            Point2D p = vp.toScreen(verts.get(i));
            xs[i] = p.getX();
            ys[i] = p.getY();
        }
        double fillAlpha = alarm ? 0.20 + 0.35 * pulse : 0.18;
        g.setFill(Color.rgb(255, 59, 48, fillAlpha));
        g.fillPolygon(xs, ys, xs.length);
        g.setLineDashes();
        g.setLineWidth(alarm ? 2 + 2 * pulse : 2);
        g.setStroke(alarm ? BORDER.interpolate(Color.WHITE, 0.6 * pulse) : BORDER);
        g.strokePolygon(xs, ys, xs.length);

        Point2D c = vp.toScreen(z.centroid());
        g.setFont(LABEL_FONT);
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.CENTER);
        String text = alarm ? z.name() + " ⚠" : z.name();
        g.setFill(Color.rgb(0, 0, 0, 0.7));
        g.fillText(text, c.getX() + 1, c.getY() + 1);
        g.setFill(Color.WHITE);
        g.fillText(text, c.getX(), c.getY());
    }

    private static void drawDraft(GraphicsContext g, MapViewport vp, List<GeoPoint> draft, GeoPoint cursor) {
        if (draft.isEmpty()) {
            return;
        }
        int n = draft.size() + (cursor != null ? 1 : 0);
        double[] xs = new double[n];
        double[] ys = new double[n];
        for (int i = 0; i < draft.size(); i++) {
            Point2D p = vp.toScreen(draft.get(i));
            xs[i] = p.getX();
            ys[i] = p.getY();
        }
        if (cursor != null) {
            Point2D p = vp.toScreen(cursor);
            xs[n - 1] = p.getX();
            ys[n - 1] = p.getY();
        }
        if (n >= 3) {
            g.setFill(Color.rgb(255, 204, 0, 0.15));
            g.fillPolygon(xs, ys, n);
        }
        g.setStroke(DRAFT);
        g.setLineWidth(2);
        g.strokePolyline(xs, ys, n);
        if (n >= 3) {
            // Closing edge back to the first vertex, dashed.
            g.setLineDashes(6, 6);
            g.strokeLine(xs[n - 1], ys[n - 1], xs[0], ys[0]);
            g.setLineDashes();
        }
        g.setFill(DRAFT);
        for (int i = 0; i < draft.size(); i++) {
            g.fillRect(xs[i] - 3, ys[i] - 3, 6, 6);
        }
    }
}
