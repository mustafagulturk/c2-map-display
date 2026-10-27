package io.github.mustafagulturk.c2map.ui;

import io.github.mustafagulturk.c2map.geo.GeoPoint;
import io.github.mustafagulturk.c2map.map.MapViewport;
import io.github.mustafagulturk.c2map.measure.Measurement;
import java.util.Objects;
import java.util.function.Consumer;
import javafx.scene.canvas.Canvas;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

/**
 * Range-and-bearing ruler. While active:
 * <ul>
 *   <li>press-drag-release with the left button measures from press to release point;</li>
 *   <li>a plain click starts a measurement that follows the mouse until the next click fixes it;</li>
 *   <li>a click after a fixed measurement starts a new one;</li>
 *   <li>Esc clears it.</li>
 * </ul>
 * Left-button map panning is disabled while the tool is active. Points are kept in geographic
 * coordinates, so the ruler stays correct when the map is zoomed. Draw it with
 * {@link MeasureLayer}. FX thread only.
 */
public final class MeasureTool {

    private enum Mode { IDLE, DRAGGING, FOLLOWING, FIXED }

    /** Mouse travel (px) below which a press-release counts as a click, not a drag. */
    private static final double CLICK_SLOP_PX = 4;

    private final MapViewport viewport;
    private final Runnable repaint;
    private final Consumer<Boolean> setPanEnabled;

    private boolean active;
    private Mode mode = Mode.IDLE;
    private GeoPoint start;
    private GeoPoint end;
    private double pressX;
    private double pressY;

    /**
     * @param viewport      map coordinate conversion
     * @param canvas        map canvas; mouse and key handlers are installed on it
     * @param repaint       requests a map repaint
     * @param setPanEnabled enables/disables left-button panning of the map
     */
    public MeasureTool(MapViewport viewport, Canvas canvas, Runnable repaint, Consumer<Boolean> setPanEnabled) {
        this.viewport = Objects.requireNonNull(viewport);
        this.repaint = Objects.requireNonNull(repaint);
        this.setPanEnabled = Objects.requireNonNull(setPanEnabled);
        canvas.addEventHandler(MouseEvent.MOUSE_PRESSED, this::onPressed);
        canvas.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::onDragged);
        canvas.addEventHandler(MouseEvent.MOUSE_RELEASED, this::onReleased);
        canvas.addEventHandler(MouseEvent.MOUSE_MOVED, this::onMoved);
        canvas.addEventHandler(KeyEvent.KEY_PRESSED, this::handleKey);
    }

    public boolean isActive() {
        return active;
    }

    /** Turns the tool on or off; turning it off clears the ruler and re-enables panning. */
    public void setActive(boolean on) {
        if (on == active) {
            return;
        }
        active = on;
        setPanEnabled.accept(!on);
        if (!on) {
            clear();
        }
    }

    /** Removes the current measurement. */
    public void clear() {
        boolean had = mode != Mode.IDLE;
        mode = Mode.IDLE;
        start = null;
        end = null;
        if (had) {
            repaint.run();
        }
    }

    /** The measurement being drawn or fixed, or null. */
    public Measurement measurement() {
        return start == null || end == null ? null : new Measurement(start, end);
    }

    /** True while the end point still follows the mouse. */
    public boolean isInProgress() {
        return mode == Mode.DRAGGING || mode == Mode.FOLLOWING;
    }

    /** Esc clears; usable from a scene-level key handler if the canvas does not have focus. */
    public void handleKey(KeyEvent e) {
        if (active && e.getCode() == KeyCode.ESCAPE && mode != Mode.IDLE) {
            clear();
            e.consume();
        }
    }

    private void onPressed(MouseEvent e) {
        if (!active || e.getButton() != MouseButton.PRIMARY) {
            return;
        }
        if (e.getSource() instanceof Canvas c) {
            c.requestFocus();
        }
        GeoPoint p = viewport.toGeo(e.getX(), e.getY());
        if (mode == Mode.FOLLOWING) {
            end = p;
            mode = Mode.FIXED;
        } else {
            start = p;
            end = p;
            pressX = e.getX();
            pressY = e.getY();
            mode = Mode.DRAGGING;
        }
        e.consume();
        repaint.run();
    }

    private void onDragged(MouseEvent e) {
        if (!active || mode != Mode.DRAGGING || !e.isPrimaryButtonDown()) {
            return;
        }
        end = viewport.toGeo(e.getX(), e.getY());
        e.consume();
        repaint.run();
    }

    private void onReleased(MouseEvent e) {
        if (!active || mode != Mode.DRAGGING || e.getButton() != MouseButton.PRIMARY) {
            return;
        }
        if (Math.hypot(e.getX() - pressX, e.getY() - pressY) < CLICK_SLOP_PX) {
            mode = Mode.FOLLOWING;
        } else {
            end = viewport.toGeo(e.getX(), e.getY());
            mode = Mode.FIXED;
        }
        e.consume();
        repaint.run();
    }

    private void onMoved(MouseEvent e) {
        if (!active || mode != Mode.FOLLOWING) {
            return;
        }
        end = viewport.toGeo(e.getX(), e.getY());
        repaint.run();
    }
}
