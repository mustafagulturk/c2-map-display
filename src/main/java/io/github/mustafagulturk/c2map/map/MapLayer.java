package io.github.mustafagulturk.c2map.map;

import javafx.scene.canvas.GraphicsContext;

/** Something drawn on top of the base map, in paint order. Called on the JavaFX thread. */
@FunctionalInterface
public interface MapLayer {

    /** @param nowMs current time in the clock of the active data source (live or replay) */
    void draw(GraphicsContext g, MapViewport viewport, long nowMs);
}
