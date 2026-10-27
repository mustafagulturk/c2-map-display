package io.github.mustafagulturk.c2map.map;

import io.github.mustafagulturk.c2map.geo.GeoPoint;
import io.github.mustafagulturk.c2map.geo.Geodesy;
import io.github.mustafagulturk.c2map.geo.WebMercator;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;
import javafx.animation.AnimationTimer;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Point2D;
import javafx.geometry.VPos;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import javafx.scene.transform.Affine;

/**
 * Offline slippy map drawn on a {@link Canvas}: Web Mercator tiles from a {@link TileStore}, then the
 * {@link MapLayer}s in order, then scale bar, attribution and cursor readout.
 *
 * <p>Mouse wheel zooms around the cursor, left drag pans (see {@link #setPanEnabled}), middle or right
 * drag always pans, keys {@code + - arrows} zoom and pan. Repaints happen on an {@link AnimationTimer},
 * at most once per frame. All methods must be called on the JavaFX thread unless noted.
 */
public final class MapView extends Region implements MapViewport {

    public static final double MIN_ZOOM = 2;
    public static final double MAX_ZOOM = 18;

    /** Zoom change per mouse-wheel notch. */
    public static final double WHEEL_ZOOM_STEP = 0.25;

    /** How many levels up a missing tile is substituted with its scaled parent. */
    public static final int MAX_PARENT_LEVELS = 3;

    public static final Color BACKGROUND = Color.web("#0d1b24");

    private static final double KEY_ZOOM_STEP = 0.5;
    private static final double KEY_PAN_PX = 100;
    private static final double SCALE_BAR_MAX_PX = 120;
    private static final double GRID_MIN_SPACING_PX = 110;
    private static final double[] GRID_STEPS_DEG =
            {0.01, 0.02, 0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10, 15, 30, 45};
    private static final Color GRID_COLOR = Color.rgb(120, 165, 190, 0.16);
    private static final Color GRID_LABEL_COLOR = Color.rgb(140, 175, 195, 0.45);
    private static final Color HUD_TEXT = Color.rgb(205, 218, 224, 0.9);
    private static final Color HUD_HALO = Color.rgb(5, 12, 16, 0.75);
    private static final Font HUD_FONT = Font.font("System", 12);
    private static final Font SMALL_FONT = Font.font("System", 10.5);
    private static final Affine IDENTITY = new Affine();

    private final Canvas canvas = new Canvas();
    private final TileStore tiles;
    private final boolean ownsTiles;
    private final List<MapLayer> layers = new CopyOnWriteArrayList<>();
    private final Set<MapLayer> failedLayers = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final FrameStats frameStats = new FrameStats();
    private final ReadOnlyObjectWrapper<GeoPoint> cursorPosition =
            new ReadOnlyObjectWrapper<>(this, "cursorPosition");
    private final AnimationTimer timer;

    /** View centre in normalised Mercator units: u in [0, 1) west to east, v in [0, 1] north to south. */
    private double centerU = 0.5;
    private double centerV = 0.5;
    private double zoom = 9;
    private double targetZoom = 9;
    private double zoomAnchorX = Double.NaN;
    private double zoomAnchorY = Double.NaN;

    private volatile boolean dirty = true;
    private boolean continuousRepaint = true;
    private boolean panEnabled = true;
    private double tileDimming = 0.3;
    private String attribution = "© OpenStreetMap katkıda bulunanlar";
    private LongSupplier timeSource = System::currentTimeMillis;
    private GeoPoint[] pendingFit;

    private MouseButton dragButton;
    private double dragLastX;
    private double dragLastY;

    /** Map on the default tile directory ({@link TileStore#defaultRoot()}); the store is closed by {@link #dispose()}. */
    public MapView() {
        this(new TileStore(), true);
    }

    /** Map on a caller-owned tile store. */
    public MapView(TileStore tiles) {
        this(tiles, false);
    }

    private MapView(TileStore tiles, boolean ownsTiles) {
        this.tiles = tiles;
        this.ownsTiles = ownsTiles;
        canvas.setManaged(false);
        getChildren().add(canvas);
        setFocusTraversable(true);
        setMinSize(0, 0);
        setCenter(new GeoPoint(38.42, 27.14));

        tiles.setOnTileLoaded(() -> dirty = true);
        installHandlers();

        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                onFrame();
            }
        };
        sceneProperty().addListener((obs, old, scene) -> {
            if (scene == null) {
                timer.stop();
            } else {
                timer.start();
            }
        });
    }

    // ---------------------------------------------------------------- public API

    /** The drawing surface; other tools may add mouse handlers to it (use addEventHandler). */
    public Canvas canvas() {
        return canvas;
    }

    public TileStore tileStore() {
        return tiles;
    }

    public FrameStats frameStats() {
        return frameStats;
    }

    /** Geographic position under the mouse, or null when the mouse is outside the map. */
    public ReadOnlyObjectProperty<GeoPoint> cursorPositionProperty() {
        return cursorPosition.getReadOnlyProperty();
    }

    public void addLayer(MapLayer layer) {
        layers.add(layer);
        dirty = true;
    }

    public void removeLayer(MapLayer layer) {
        layers.remove(layer);
        dirty = true;
    }

    /** The layers in paint order (read-only). */
    public List<MapLayer> layers() {
        return Collections.unmodifiableList(layers);
    }

    /** Clock passed to the layers as {@code nowMs} (e.g. replay time); default is the wall clock. */
    public void setTimeSource(LongSupplier nowMs) {
        timeSource = nowMs == null ? System::currentTimeMillis : nowMs;
        dirty = true;
    }

    /** Asks for a redraw on the next frame. Safe to call from any thread. */
    public void requestRepaint() {
        dirty = true;
    }

    /**
     * When true (the default) the map redraws every frame, so time-dependent layers animate; when false
     * it redraws only after {@link #requestRepaint()}, view changes or tile arrivals.
     */
    public void setContinuousRepaint(boolean continuous) {
        continuousRepaint = continuous;
    }

    /** Whether a left-button drag pans the map. Middle and right drags always pan. */
    public void setPanEnabled(boolean enabled) {
        panEnabled = enabled;
        if (!enabled && dragButton == MouseButton.PRIMARY) {
            endDrag();
        }
    }

    public boolean isPanEnabled() {
        return panEnabled;
    }

    /** Opacity (0..1) of the dark wash drawn over the tiles so coloured symbols stand out. */
    public void setTileDimming(double opacity) {
        tileDimming = Math.max(0, Math.min(1, opacity));
        dirty = true;
    }

    public double getTileDimming() {
        return tileDimming;
    }

    /** Attribution text drawn bottom-right. */
    public void setAttribution(String text) {
        attribution = text == null ? "" : text;
        dirty = true;
    }

    public void setCenter(GeoPoint center) {
        centerU = WebMercator.lonToX(center.lon(), 0) / WebMercator.TILE_SIZE;
        centerV = WebMercator.latToY(center.lat(), 0) / WebMercator.TILE_SIZE;
        normaliseCenter();
        dirty = true;
    }

    public GeoPoint getCenter() {
        return WebMercator.fromPixel(centerU * WebMercator.TILE_SIZE, centerV * WebMercator.TILE_SIZE, 0);
    }

    /** Sets the zoom immediately (clamped to [{@link #MIN_ZOOM}, {@link #MAX_ZOOM}]), keeping the centre. */
    public void setZoom(double z) {
        zoom = clampZoom(z);
        targetZoom = zoom;
        dirty = true;
    }

    public double getZoom() {
        return zoom;
    }

    /** Centres the view on the box spanned by two corners and zooms so it fits with a small margin. */
    public void fitTo(GeoPoint a, GeoPoint b) {
        if (getWidth() <= 0 || getHeight() <= 0) {
            pendingFit = new GeoPoint[] {a, b};
            return;
        }
        pendingFit = null;
        double u0 = WebMercator.lonToX(a.lon(), 0) / WebMercator.TILE_SIZE;
        double u1 = WebMercator.lonToX(b.lon(), 0) / WebMercator.TILE_SIZE;
        double v0 = WebMercator.latToY(a.lat(), 0) / WebMercator.TILE_SIZE;
        double v1 = WebMercator.latToY(b.lat(), 0) / WebMercator.TILE_SIZE;
        centerU = (u0 + u1) / 2;
        centerV = (v0 + v1) / 2;
        normaliseCenter();
        double margin = 24;
        double du = Math.abs(u1 - u0) * WebMercator.TILE_SIZE;
        double dv = Math.abs(v1 - v0) * WebMercator.TILE_SIZE;
        double fitW = Math.max(1, getWidth() - 2 * margin);
        double fitH = Math.max(1, getHeight() - 2 * margin);
        double scale = Math.min(du > 0 ? fitW / du : Double.POSITIVE_INFINITY,
                dv > 0 ? fitH / dv : Double.POSITIVE_INFINITY);
        if (Double.isFinite(scale)) {
            setZoom(Math.log(scale) / Math.log(2));
        }
        dirty = true;
    }

    /** Stops the repaint timer and closes the tile store if this view created it. */
    public void dispose() {
        timer.stop();
        if (ownsTiles) {
            tiles.close();
        }
    }

    /** Operator readout of a position, e.g. {@code 38.42000° K  27.14000° D}. */
    public static String formatPosition(GeoPoint p) {
        return String.format(Locale.ROOT, "%.5f° %s   %.5f° %s",
                Math.abs(p.lat()), p.lat() >= 0 ? "K" : "G",
                Math.abs(p.lon()), p.lon() >= 0 ? "D" : "B");
    }

    // ---------------------------------------------------------------- MapViewport

    @Override
    public Point2D toScreen(GeoPoint p) {
        double ws = WebMercator.worldSize(zoom);
        double du = WebMercator.lonToX(p.lon(), 0) / WebMercator.TILE_SIZE - centerU;
        du -= Math.rint(du); // nearest copy of the world, so tracks near the antimeridian stay visible
        double dv = WebMercator.latToY(p.lat(), 0) / WebMercator.TILE_SIZE - centerV;
        return new Point2D(du * ws + canvas.getWidth() / 2, dv * ws + canvas.getHeight() / 2);
    }

    @Override
    public GeoPoint toGeo(double x, double y) {
        double ws = WebMercator.worldSize(zoom);
        double u = centerU + (x - canvas.getWidth() / 2) / ws;
        double v = centerV + (y - canvas.getHeight() / 2) / ws;
        return WebMercator.fromPixel(u * WebMercator.TILE_SIZE, v * WebMercator.TILE_SIZE, 0);
    }

    @Override
    public double metersPerPixel() {
        return WebMercator.metersPerPixel(getCenter().lat(), zoom);
    }

    @Override
    public double zoom() {
        return zoom;
    }

    @Override
    public double width() {
        return canvas.getWidth();
    }

    @Override
    public double height() {
        return canvas.getHeight();
    }

    // ---------------------------------------------------------------- layout

    @Override
    protected void layoutChildren() {
        double w = snapSizeX(getWidth());
        double h = snapSizeY(getHeight());
        if (canvas.getWidth() != w || canvas.getHeight() != h) {
            canvas.setWidth(w);
            canvas.setHeight(h);
            dirty = true;
        }
        if (pendingFit != null && w > 0 && h > 0) {
            fitTo(pendingFit[0], pendingFit[1]);
        }
    }

    @Override
    protected double computePrefWidth(double height) {
        return 1024;
    }

    @Override
    protected double computePrefHeight(double width) {
        return 768;
    }

    // ---------------------------------------------------------------- interaction

    private void installHandlers() {
        canvas.addEventHandler(ScrollEvent.SCROLL, e -> {
            if (e.getDeltaY() == 0) {
                return;
            }
            double notches = e.getDeltaY() / 40.0;
            targetZoom = clampZoom(targetZoom + notches * WHEEL_ZOOM_STEP);
            zoomAnchorX = e.getX();
            zoomAnchorY = e.getY();
            e.consume();
        });
        canvas.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            requestFocus();
            if (dragButton == null && canPan(e.getButton())) {
                dragButton = e.getButton();
                dragLastX = e.getX();
                dragLastY = e.getY();
            }
        });
        canvas.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
            updateCursor(e.getX(), e.getY());
            if (dragButton == null) {
                return;
            }
            double ws = WebMercator.worldSize(zoom);
            centerU -= (e.getX() - dragLastX) / ws;
            centerV -= (e.getY() - dragLastY) / ws;
            normaliseCenter();
            dragLastX = e.getX();
            dragLastY = e.getY();
            canvas.setCursor(Cursor.CLOSED_HAND);
            dirty = true;
        });
        canvas.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> {
            if (e.getButton() == dragButton) {
                endDrag();
            }
        });
        canvas.addEventHandler(MouseEvent.MOUSE_MOVED, e -> updateCursor(e.getX(), e.getY()));
        canvas.addEventHandler(MouseEvent.MOUSE_EXITED, e -> {
            if (dragButton == null) {
                cursorPosition.set(null);
                dirty = true;
            }
        });
        addEventHandler(KeyEvent.KEY_PRESSED, this::onKey);
    }

    private boolean canPan(MouseButton b) {
        return b == MouseButton.MIDDLE || b == MouseButton.SECONDARY || (b == MouseButton.PRIMARY && panEnabled);
    }

    private void endDrag() {
        dragButton = null;
        canvas.setCursor(null);
    }

    private void updateCursor(double x, double y) {
        cursorPosition.set(toGeo(x, y));
        dirty = true;
    }

    private void onKey(KeyEvent e) {
        double w = canvas.getWidth() / 2;
        double h = canvas.getHeight() / 2;
        switch (e.getCode()) {
            case PLUS, ADD, EQUALS -> animateZoom(targetZoom + KEY_ZOOM_STEP, w, h);
            case MINUS, SUBTRACT -> animateZoom(targetZoom - KEY_ZOOM_STEP, w, h);
            case LEFT -> panPixels(-KEY_PAN_PX, 0);
            case RIGHT -> panPixels(KEY_PAN_PX, 0);
            case UP -> panPixels(0, -KEY_PAN_PX);
            case DOWN -> panPixels(0, KEY_PAN_PX);
            default -> {
                return;
            }
        }
        e.consume();
    }

    private void animateZoom(double z, double anchorX, double anchorY) {
        targetZoom = clampZoom(z);
        zoomAnchorX = anchorX;
        zoomAnchorY = anchorY;
    }

    private void panPixels(double dx, double dy) {
        double ws = WebMercator.worldSize(zoom);
        centerU += dx / ws;
        centerV += dy / ws;
        normaliseCenter();
        dirty = true;
    }

    /** Changes zoom keeping the geographic point under screen (x, y) fixed. */
    private void zoomAround(double newZoom, double x, double y) {
        double ox = x - canvas.getWidth() / 2;
        double oy = y - canvas.getHeight() / 2;
        double before = WebMercator.worldSize(zoom);
        double after = WebMercator.worldSize(newZoom);
        centerU += ox / before - ox / after;
        centerV += oy / before - oy / after;
        zoom = newZoom;
        normaliseCenter();
        dirty = true;
    }

    private void normaliseCenter() {
        centerU -= Math.floor(centerU);
        centerV = Math.max(0, Math.min(1, centerV));
    }

    private static double clampZoom(double z) {
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, z));
    }

    // ---------------------------------------------------------------- drawing

    private void onFrame() {
        if (zoom != targetZoom) {
            double next = zoom + (targetZoom - zoom) * 0.35;
            if (Math.abs(targetZoom - next) < 0.002) {
                next = targetZoom;
            }
            double ax = Double.isNaN(zoomAnchorX) ? canvas.getWidth() / 2 : zoomAnchorX;
            double ay = Double.isNaN(zoomAnchorY) ? canvas.getHeight() / 2 : zoomAnchorY;
            zoomAround(next, ax, ay);
            if (dragButton == null && cursorPosition.get() != null && !Double.isNaN(zoomAnchorX)) {
                cursorPosition.set(toGeo(zoomAnchorX, zoomAnchorY));
            }
        }
        if (continuousRepaint || dirty) {
            dirty = false;
            redraw();
        }
    }

    private void redraw() {
        long t0 = System.nanoTime();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setTransform(IDENTITY);
        g.setGlobalAlpha(1);
        g.setFill(BACKGROUND);
        g.fillRect(0, 0, w, h);

        drawGrid(g, w, h);
        drawTiles(g, w, h);
        if (tileDimming > 0) {
            g.setFill(Color.rgb(13, 27, 36, tileDimming));
            g.fillRect(0, 0, w, h);
        }

        long now = timeSource.getAsLong();
        for (MapLayer layer : layers) {
            g.save();
            try {
                layer.draw(g, this, now);
            } catch (RuntimeException ex) {
                if (failedLayers.add(layer)) {
                    System.getLogger(MapView.class.getName())
                            .log(System.Logger.Level.WARNING, "map layer failed: " + layer, ex);
                }
            } finally {
                g.restore();
            }
        }

        drawScaleBar(g, h);
        drawAttributionAndCursor(g, w, h);
        frameStats.record(t0, System.nanoTime() - t0);
    }

    private void drawTiles(GraphicsContext g, double w, double h) {
        int z = (int) Math.floor(zoom);
        int n = 1 << z;
        double tileSize = WebMercator.worldSize(zoom) / n;
        double originX = w / 2 - centerU * n * tileSize;
        double originY = h / 2 - centerV * n * tileSize;
        int tx0 = (int) Math.floor(-originX / tileSize);
        int tx1 = (int) Math.floor((w - originX) / tileSize);
        int ty0 = Math.max(0, (int) Math.floor(-originY / tileSize));
        int ty1 = Math.min(n - 1, (int) Math.floor((h - originY) / tileSize));
        g.setImageSmoothing(true);
        for (int ty = ty0; ty <= ty1; ty++) {
            double y0 = Math.floor(originY + ty * tileSize);
            double y1 = Math.floor(originY + (ty + 1) * tileSize);
            for (int tx = tx0; tx <= tx1; tx++) {
                double x0 = Math.floor(originX + tx * tileSize);
                double x1 = Math.floor(originX + (tx + 1) * tileSize);
                drawTile(g, new TileKey(z, Math.floorMod(tx, n), ty), x0, y0, x1 - x0, y1 - y0);
            }
        }
    }

    private void drawTile(GraphicsContext g, TileKey key, double x, double y, double w, double h) {
        Image img = tiles.get(key);
        if (img != null) {
            g.drawImage(img, x, y, w, h);
            return;
        }
        boolean missing = tiles.isMissing(key);
        for (int d = 1; d <= MAX_PARENT_LEVELS && d <= key.z(); d++) {
            TileKey parent = new TileKey(key.z() - d, key.x() >> d, key.y() >> d);
            // Load ancestors from disk only when the tile itself is known to be absent (over-zoom).
            Image p = missing ? tiles.get(parent) : tiles.peek(parent);
            if (p != null) {
                int mask = (1 << d) - 1;
                double sub = p.getWidth() / (1 << d);
                g.drawImage(p, (key.x() & mask) * sub, (key.y() & mask) * sub, sub, sub, x, y, w, h);
                return;
            }
        }
    }

    private void drawGrid(GraphicsContext g, double w, double h) {
        double ws = WebMercator.worldSize(zoom);
        double degPerPx = 360.0 / ws;
        double step = GRID_STEPS_DEG[GRID_STEPS_DEG.length - 1];
        for (double s : GRID_STEPS_DEG) {
            if (s / degPerPx >= GRID_MIN_SPACING_PX) {
                step = s;
                break;
            }
        }
        int decimals = step >= 1 ? 0 : step >= 0.1 ? (step == 0.25 ? 2 : 1) : 2;
        g.setStroke(GRID_COLOR);
        g.setLineWidth(1);
        g.setFont(SMALL_FONT);
        g.setFill(GRID_LABEL_COLOR);
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.TOP);

        double lonLeft = (centerU - w / 2 / ws) * 360 - 180;
        double lonRight = (centerU + w / 2 / ws) * 360 - 180;
        for (double lon = Math.ceil(lonLeft / step) * step; lon <= lonRight; lon += step) {
            double x = Math.floor(((lon + 180) / 360 - centerU) * ws + w / 2) + 0.5;
            g.strokeLine(x, 0, x, h);
            g.fillText(degLabel(Geodesy.normalizeLon(lon), decimals, "D", "B"), x + 3, 3);
        }
        double latTop = toGeo(0, 0).lat();
        double latBottom = toGeo(0, h).lat();
        for (double lat = Math.ceil(latBottom / step) * step; lat <= latTop; lat += step) {
            double y = Math.floor(toScreen(new GeoPoint(lat, 0)).getY()) + 0.5;
            g.strokeLine(0, y, w, y);
            g.fillText(degLabel(lat, decimals, "K", "G"), 3, y + 2);
        }
    }

    private static String degLabel(double v, int decimals, String pos, String neg) {
        double a = Math.abs(v);
        if (a < 1e-7) {
            return "0°";
        }
        return String.format(Locale.ROOT, "%." + decimals + "f°%s", a, v > 0 ? pos : neg);
    }

    private void drawScaleBar(GraphicsContext g, double h) {
        double mpp = metersPerPixel();
        if (!(mpp > 0)) {
            return;
        }
        double meters = ScaleBar.niceLength(mpp * SCALE_BAR_MAX_PX);
        double px = Math.round(meters / mpp);
        double x0 = 14.5;
        double y = h - 16.5;
        g.setLineWidth(4);
        g.setStroke(HUD_HALO);
        g.strokeLine(x0, y, x0 + px, y);
        g.strokeLine(x0, y - 6, x0, y + 1);
        g.strokeLine(x0 + px, y - 6, x0 + px, y + 1);
        g.setLineWidth(1.5);
        g.setStroke(HUD_TEXT);
        g.strokeLine(x0, y, x0 + px, y);
        g.strokeLine(x0, y - 6, x0, y);
        g.strokeLine(x0 + px, y - 6, x0 + px, y);
        g.setFont(HUD_FONT);
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.BOTTOM);
        haloText(g, ScaleBar.label(meters), x0 + px / 2, y - 4);
    }

    private void drawAttributionAndCursor(GraphicsContext g, double w, double h) {
        g.setTextAlign(TextAlignment.RIGHT);
        g.setTextBaseline(VPos.BOTTOM);
        g.setFont(SMALL_FONT);
        haloText(g, attribution, w - 8, h - 5);
        GeoPoint cursor = cursorPosition.get();
        if (cursor != null) {
            g.setFont(HUD_FONT);
            haloText(g, formatPosition(cursor), w - 8, h - 22);
        }
    }

    private static void haloText(GraphicsContext g, String text, double x, double y) {
        g.setLineWidth(3);
        g.setStroke(HUD_HALO);
        g.strokeText(text, x, y);
        g.setFill(HUD_TEXT);
        g.fillText(text, x, y);
    }
}
