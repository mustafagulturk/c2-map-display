package io.github.mustafagulturk.c2map.ui;

import io.github.mustafagulturk.c2map.geo.GeoPoint;
import io.github.mustafagulturk.c2map.map.MapViewport;
import io.github.mustafagulturk.c2map.zone.RestrictedZone;
import io.github.mustafagulturk.c2map.zone.ZoneMonitor;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import javafx.scene.canvas.Canvas;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

/**
 * Interactive drawing of restricted zones. While active:
 * <ul>
 *   <li>each left click adds a vertex; the open polygon follows the mouse (rubber band);</li>
 *   <li>double-click or Enter closes it (at least 3 vertices) and adds a "Bölge-N" zone;</li>
 *   <li>Backspace or right-click while drawing removes the last vertex;</li>
 *   <li>Esc cancels the drawing;</li>
 *   <li>right-click on an existing zone (when not drawing) deletes it.</li>
 * </ul>
 * Left-button map panning is disabled while active. The draft is drawn by {@link ZoneLayer}.
 * FX thread only.
 */
public final class ZoneEditor {

    /** Polygons smaller than this (m²) are rejected as accidental. */
    private static final double MIN_AREA_M2 = 100;

    private final MapViewport viewport;
    private final ZoneMonitor monitor;
    private final Runnable repaint;
    private final Consumer<Boolean> setPanEnabled;

    private boolean active;
    private final List<GeoPoint> draft = new ArrayList<>();
    private GeoPoint cursor;
    private Consumer<String> statusSink = s -> { };

    /**
     * @param viewport      map coordinate conversion
     * @param canvas        map canvas; mouse and key handlers are installed on it
     * @param monitor       receives the finished zones
     * @param repaint       requests a map repaint
     * @param setPanEnabled enables/disables left-button panning of the map
     */
    public ZoneEditor(MapViewport viewport, Canvas canvas, ZoneMonitor monitor, Runnable repaint,
            Consumer<Boolean> setPanEnabled) {
        this.viewport = Objects.requireNonNull(viewport);
        this.monitor = Objects.requireNonNull(monitor);
        this.repaint = Objects.requireNonNull(repaint);
        this.setPanEnabled = Objects.requireNonNull(setPanEnabled);
        canvas.addEventHandler(MouseEvent.MOUSE_PRESSED, this::onPressed);
        canvas.addEventHandler(MouseEvent.MOUSE_MOVED, this::onMoved);
        canvas.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::onMoved);
        canvas.addEventHandler(MouseEvent.MOUSE_EXITED, e -> {
            cursor = null;
            repaint.run();
        });
        canvas.addEventHandler(KeyEvent.KEY_PRESSED, this::handleKey);
    }

    /** Receives short Turkish hints for a status bar (e.g. "En az 3 köşe gerekli"). */
    public void setStatusSink(Consumer<String> sink) {
        statusSink = Objects.requireNonNull(sink);
    }

    public boolean isActive() {
        return active;
    }

    /** Turns drawing mode on or off; turning it off discards an unfinished polygon. */
    public void setActive(boolean on) {
        if (on == active) {
            return;
        }
        active = on;
        setPanEnabled.accept(!on);
        cancel();
        if (on) {
            statusSink.accept("Bölge çizimi: köşe eklemek için tıklayın, bitirmek için çift tıklayın veya Enter'a basın");
        }
    }

    /** Vertices of the polygon being drawn (empty when not drawing). */
    public List<GeoPoint> draft() {
        return List.copyOf(draft);
    }

    /** Mouse position for the rubber band, or null. */
    public GeoPoint cursor() {
        return active ? cursor : null;
    }

    /** Discards the polygon being drawn. */
    public void cancel() {
        boolean had = !draft.isEmpty();
        draft.clear();
        if (had) {
            repaint.run();
        }
    }

    /**
     * Closes the draft into a zone if it has at least 3 vertices and a non-trivial area.
     *
     * @return the created zone, or empty if the draft was not valid (it is kept for further editing)
     */
    public Optional<RestrictedZone> finish() {
        List<GeoPoint> pts = dedupe(draft);
        if (pts.size() < 3) {
            statusSink.accept("Bölge için en az 3 köşe gerekli");
            return Optional.empty();
        }
        RestrictedZone probe = new RestrictedZone("draft", "draft", pts, 0);
        if (probe.areaM2() < MIN_AREA_M2) {
            statusSink.accept("Bölge çok küçük");
            return Optional.empty();
        }
        RestrictedZone zone = monitor.createZone(pts);
        draft.clear();
        statusSink.accept(zone.name() + " eklendi");
        repaint.run();
        return Optional.of(zone);
    }

    /** Deletes a zone by id. */
    public boolean deleteZone(String id) {
        boolean removed = monitor.removeZone(id);
        if (removed) {
            repaint.run();
        }
        return removed;
    }

    /** Esc / Enter / Backspace handling; usable from a scene-level key handler. */
    public void handleKey(KeyEvent e) {
        if (!active) {
            return;
        }
        if (e.getCode() == KeyCode.ESCAPE && !draft.isEmpty()) {
            cancel();
            statusSink.accept("Bölge çizimi iptal edildi");
            e.consume();
        } else if (e.getCode() == KeyCode.ENTER && !draft.isEmpty()) {
            finish();
            e.consume();
        } else if (e.getCode() == KeyCode.BACK_SPACE && !draft.isEmpty()) {
            draft.remove(draft.size() - 1);
            repaint.run();
            e.consume();
        }
    }

    private void onPressed(MouseEvent e) {
        if (!active) {
            return;
        }
        if (e.getSource() instanceof Canvas c) {
            c.requestFocus();
        }
        GeoPoint p = viewport.toGeo(e.getX(), e.getY());
        if (e.getButton() == MouseButton.PRIMARY) {
            if (e.getClickCount() >= 2) {
                finish();
            } else {
                draft.add(p);
                cursor = p;
                repaint.run();
            }
            e.consume();
        } else if (e.getButton() == MouseButton.SECONDARY) {
            if (!draft.isEmpty()) {
                draft.remove(draft.size() - 1);
                repaint.run();
            } else {
                monitor.zoneAt(p).ifPresent(z -> {
                    deleteZone(z.id());
                    statusSink.accept(z.name() + " silindi");
                });
            }
            e.consume();
        }
    }

    private void onMoved(MouseEvent e) {
        if (!active) {
            return;
        }
        cursor = viewport.toGeo(e.getX(), e.getY());
        if (!draft.isEmpty()) {
            repaint.run();
        }
    }

    /** Drops consecutive duplicates (a double-click adds the same point twice) and a closing duplicate. */
    private static List<GeoPoint> dedupe(List<GeoPoint> pts) {
        List<GeoPoint> out = new ArrayList<>();
        for (GeoPoint p : pts) {
            if (out.isEmpty() || !out.get(out.size() - 1).equals(p)) {
                out.add(p);
            }
        }
        if (out.size() > 1 && out.get(0).equals(out.get(out.size() - 1))) {
            out.remove(out.size() - 1);
        }
        return out;
    }
}
