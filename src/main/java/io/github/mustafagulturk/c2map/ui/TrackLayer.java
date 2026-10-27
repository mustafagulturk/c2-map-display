package io.github.mustafagulturk.c2map.ui;

import io.github.mustafagulturk.c2map.geo.GeoPoint;
import io.github.mustafagulturk.c2map.map.MapLayer;
import io.github.mustafagulturk.c2map.map.MapViewport;
import io.github.mustafagulturk.c2map.track.Identity;
import io.github.mustafagulturk.c2map.track.TrackSnapshot;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import javafx.geometry.Point2D;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/**
 * Draws all tracks: trail, velocity leader (distance covered in 60 s), identity symbol and label.
 *
 * <p>Built for thousands of tracks: tracks outside the view are culled with a cheap lat/lon test
 * before any projection, per-frame buffers are reused, colours are set once per identity, trails are
 * simplified and labels are skipped when the picture is crowded or zoomed far out. Tracks not updated
 * for a while are drawn dimmed. Call from the JavaFX thread only.
 */
public final class TrackLayer implements MapLayer {

    /** Hit-test radius in pixels. */
    public static final double HIT_RADIUS_PX = 12.0;
    /** Leader length represents the distance covered in this many seconds. */
    public static final double LEADER_SECONDS = 60.0;
    private static final double MAX_LEADER_PX = 150.0;
    /** Above this many visible tracks labels are hidden (except the selected track). */
    private static final int MAX_LABELLED = 300;
    /** Labels are hidden when zoomed out beyond this many metres per pixel. */
    private static final double MAX_LABEL_MPP = 400.0;
    /** Fading-dot trails up to this many visible tracks; thin polylines above it. */
    private static final int MAX_DOT_TRAILS = 300;
    /** Above this many visible tracks only the newest trail points are drawn. */
    private static final int SHORT_TRAIL_THRESHOLD = 1500;
    private static final int SHORT_TRAIL_POINTS = 8;
    /** Above this many visible tracks symbols are drawn as outlines only (much cheaper to rasterise). */
    private static final int OUTLINE_ONLY_THRESHOLD = 1000;
    /** Tracks not updated for this long are drawn dimmed. */
    private static final long STALE_MS = 6_000;
    private static final double CULL_MARGIN_PX = 40.0;

    private static final Color SELECT = Color.WHITE;
    private static final Color LABEL = Color.rgb(235, 240, 245);
    private static final Color LABEL_SHADOW = Color.rgb(0, 0, 0, 0.75);
    private static final Identity[] IDENTITIES = Identity.values();

    private final Supplier<List<TrackSnapshot>> source;
    private String selectedKey;
    private boolean showTrails = true;
    private boolean showLabels = true;
    private Font font;

    // Reused per-frame buffers of the visible tracks.
    private TrackSnapshot[] visible = new TrackSnapshot[256];
    private double[] vx = new double[256];
    private double[] vy = new double[256];
    private double[] polyX = new double[64];
    private double[] polyY = new double[64];

    public TrackLayer(Supplier<List<TrackSnapshot>> source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /** Highlights the track with this key; {@code null} clears the selection. */
    public void setSelected(String key) {
        this.selectedKey = key;
    }

    public String selected() {
        return selectedKey;
    }

    public void setShowTrails(boolean show) {
        this.showTrails = show;
    }

    public boolean showTrails() {
        return showTrails;
    }

    public void setShowLabels(boolean show) {
        this.showLabels = show;
    }

    public boolean showLabels() {
        return showLabels;
    }

    /** The track whose symbol is nearest to screen point {@code (x, y)}, within {@link #HIT_RADIUS_PX}. */
    public Optional<TrackSnapshot> trackAt(MapViewport viewport, double x, double y) {
        TrackSnapshot best = null;
        double bestD2 = HIT_RADIUS_PX * HIT_RADIUS_PX;
        Bounds b = Bounds.of(viewport, CULL_MARGIN_PX);
        for (TrackSnapshot t : source.get()) {
            if (!b.contains(t.position())) {
                continue;
            }
            Point2D p = viewport.toScreen(t.position());
            double dx = p.getX() - x;
            double dy = p.getY() - y;
            double d2 = dx * dx + dy * dy;
            if (d2 <= bestD2) {
                bestD2 = d2;
                best = t;
            }
        }
        return Optional.ofNullable(best);
    }

    @Override
    public void draw(GraphicsContext g, MapViewport viewport, long nowMs) {
        List<TrackSnapshot> tracks = source.get();
        if (tracks.isEmpty()) {
            return;
        }
        int n = collectVisible(tracks, viewport);
        if (n == 0) {
            return;
        }
        double mpp = viewport.metersPerPixel();
        g.save();
        g.setLineDashes((double[]) null);
        if (showTrails) {
            drawTrails(g, viewport, n);
        }
        drawLeaders(g, n, mpp, nowMs);
        drawSymbols(g, n, nowMs);
        drawSelection(g, n);
        boolean labelAll = showLabels && n <= MAX_LABELLED && mpp <= MAX_LABEL_MPP;
        if (labelAll || selectedKey != null) {
            drawLabels(g, n, labelAll);
        }
        g.restore();
        // Do not keep snapshots alive between frames.
        Arrays.fill(visible, 0, n, null);
    }

    private int collectVisible(List<TrackSnapshot> tracks, MapViewport viewport) {
        Bounds b = Bounds.of(viewport, CULL_MARGIN_PX);
        double w = viewport.width();
        double h = viewport.height();
        int n = 0;
        for (TrackSnapshot t : tracks) {
            if (!b.contains(t.position())) {
                continue;
            }
            Point2D p = viewport.toScreen(t.position());
            double x = p.getX();
            double y = p.getY();
            if (x < -CULL_MARGIN_PX || y < -CULL_MARGIN_PX || x > w + CULL_MARGIN_PX || y > h + CULL_MARGIN_PX) {
                continue;
            }
            if (n == visible.length) {
                int cap = n * 2;
                visible = Arrays.copyOf(visible, cap);
                vx = Arrays.copyOf(vx, cap);
                vy = Arrays.copyOf(vy, cap);
            }
            visible[n] = t;
            vx[n] = x;
            vy[n] = y;
            n++;
        }
        return n;
    }

    private void drawTrails(GraphicsContext g, MapViewport viewport, int n) {
        boolean dots = n <= MAX_DOT_TRAILS;
        int maxPoints = n > SHORT_TRAIL_THRESHOLD ? SHORT_TRAIL_POINTS : Integer.MAX_VALUE;
        for (Identity id : IDENTITIES) {
            Color c = Symbology.frameColor(id);
            if (dots) {
                g.setFill(c);
            } else {
                g.setStroke(c);
                g.setLineWidth(1);
                g.setGlobalAlpha(0.35);
            }
            for (int i = 0; i < n; i++) {
                TrackSnapshot t = visible[i];
                List<GeoPoint> hist = t.history();
                if (t.identity() != id || hist.isEmpty()) {
                    continue;
                }
                int size = hist.size();
                int from = Math.max(0, size - maxPoints);
                if (dots) {
                    int count = size - from;
                    for (int k = from; k < size; k++) {
                        Point2D p = viewport.toScreen(hist.get(k));
                        // Oldest points fade out.
                        g.setGlobalAlpha(0.15 + 0.6 * (k - from + 1) / count);
                        g.fillRect(p.getX() - 1.5, p.getY() - 1.5, 3, 3);
                    }
                } else {
                    int count = size - from + 1;
                    ensurePoly(count);
                    int k = 0;
                    for (int j = from; j < size; j++) {
                        Point2D p = viewport.toScreen(hist.get(j));
                        polyX[k] = p.getX();
                        polyY[k] = p.getY();
                        k++;
                    }
                    polyX[k] = vx[i];
                    polyY[k] = vy[i];
                    g.strokePolyline(polyX, polyY, count);
                }
            }
        }
        g.setGlobalAlpha(1);
    }

    private void ensurePoly(int count) {
        if (polyX.length < count) {
            polyX = new double[Math.max(count, polyX.length * 2)];
            polyY = new double[polyX.length];
        }
    }

    private void drawLeaders(GraphicsContext g, int n, double mpp, long nowMs) {
        if (!(mpp > 0)) {
            return;
        }
        g.setLineWidth(1.5);
        for (Identity id : IDENTITIES) {
            g.setStroke(Symbology.frameColor(id));
            for (int i = 0; i < n; i++) {
                TrackSnapshot t = visible[i];
                if (t.identity() != id) {
                    continue;
                }
                double len = Math.min(MAX_LEADER_PX, t.speedMps() * LEADER_SECONDS / mpp);
                if (len < Symbology.SYMBOL_SIZE / 2 + 2) {
                    continue; // would stay inside the symbol
                }
                double rad = Math.toRadians(t.courseDeg());
                double sin = Math.sin(rad);
                double cos = Math.cos(rad);
                double r0 = Symbology.SYMBOL_SIZE / 2;
                g.setGlobalAlpha(stale(t, nowMs) ? 0.45 : 1.0);
                // From the symbol edge so the frame stays clean; the length still counts from the centre.
                g.strokeLine(vx[i] + sin * r0, vy[i] - cos * r0, vx[i] + sin * len, vy[i] - cos * len);
            }
        }
        g.setGlobalAlpha(1);
    }

    private void drawSymbols(GraphicsContext g, int n, long nowMs) {
        boolean fill = n <= OUTLINE_ONLY_THRESHOLD;
        g.setLineWidth(fill ? 2 : 1.5);
        for (Identity id : IDENTITIES) {
            g.setFill(Symbology.fillColor(id));
            g.setStroke(Symbology.frameColor(id));
            for (int i = 0; i < n; i++) {
                TrackSnapshot t = visible[i];
                if (t.identity() != id) {
                    continue;
                }
                g.setGlobalAlpha(stale(t, nowMs) ? 0.45 : 1.0);
                Symbology.drawFrame(g, id, Symbology.isAir(t), vx[i], vy[i], Symbology.SYMBOL_SIZE, fill);
            }
        }
        g.setGlobalAlpha(1);
    }

    private void drawSelection(GraphicsContext g, int n) {
        String key = selectedKey;
        if (key == null) {
            return;
        }
        for (int i = 0; i < n; i++) {
            if (key.equals(visible[i].key())) {
                double h = Symbology.SYMBOL_SIZE / 2 + 6;
                double arm = 6;
                double x0 = vx[i] - h;
                double y0 = vy[i] - h;
                double x1 = vx[i] + h;
                double y1 = vy[i] + h;
                g.setStroke(SELECT);
                g.setLineWidth(2);
                // Corner brackets.
                g.strokePolyline(new double[] {x0, x0, x0 + arm}, new double[] {y0 + arm, y0, y0}, 3);
                g.strokePolyline(new double[] {x1 - arm, x1, x1}, new double[] {y0, y0, y0 + arm}, 3);
                g.strokePolyline(new double[] {x1, x1, x1 - arm}, new double[] {y1 - arm, y1, y1}, 3);
                g.strokePolyline(new double[] {x0 + arm, x0, x0}, new double[] {y1, y1, y1 - arm}, 3);
                return;
            }
        }
    }

    private void drawLabels(GraphicsContext g, int n, boolean all) {
        if (font == null) {
            font = Font.font("Monospaced", 11);
        }
        g.setFont(font);
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.CENTER);
        double dx = Symbology.SYMBOL_SIZE / 2 + 6;
        String key = selectedKey;
        for (int i = 0; i < n; i++) {
            TrackSnapshot t = visible[i];
            if (!all && !t.key().equals(key)) {
                continue;
            }
            String text = Symbology.label(t);
            double x = vx[i] + dx;
            double y = vy[i] - 2;
            g.setFill(LABEL_SHADOW);
            g.fillText(text, x + 1, y + 1);
            g.setFill(t.key().equals(key) ? SELECT : LABEL);
            g.fillText(text, x, y);
        }
    }

    private static boolean stale(TrackSnapshot t, long nowMs) {
        return nowMs - t.updatedAtMs() > STALE_MS;
    }

    /** Geographic bounding box of the view, for culling before projecting. */
    record Bounds(double minLat, double maxLat, double minLon, double maxLon, boolean enabled) {

        static Bounds of(MapViewport vp, double marginPx) {
            double w = vp.width();
            double h = vp.height();
            GeoPoint a = vp.toGeo(-marginPx, -marginPx);
            GeoPoint b = vp.toGeo(w + marginPx, -marginPx);
            GeoPoint c = vp.toGeo(-marginPx, h + marginPx);
            GeoPoint d = vp.toGeo(w + marginPx, h + marginPx);
            double minLat = Math.min(Math.min(a.lat(), b.lat()), Math.min(c.lat(), d.lat()));
            double maxLat = Math.max(Math.max(a.lat(), b.lat()), Math.max(c.lat(), d.lat()));
            double minLon = Math.min(Math.min(a.lon(), b.lon()), Math.min(c.lon(), d.lon()));
            double maxLon = Math.max(Math.max(a.lon(), b.lon()), Math.max(c.lon(), d.lon()));
            // Across the antimeridian or at world scale the box is unreliable: rely on screen culling.
            boolean ok = minLon < maxLon && maxLon - minLon < 180;
            return new Bounds(minLat, maxLat, minLon, maxLon, ok);
        }

        boolean contains(GeoPoint p) {
            return !enabled || (p.lat() >= minLat && p.lat() <= maxLat && p.lon() >= minLon && p.lon() <= maxLon);
        }
    }
}
