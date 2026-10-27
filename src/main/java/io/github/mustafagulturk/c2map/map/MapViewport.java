package io.github.mustafagulturk.c2map.map;

import io.github.mustafagulturk.c2map.geo.GeoPoint;
import javafx.geometry.Point2D;

/** Converts between geographic and screen (canvas pixel) coordinates for the current view. */
public interface MapViewport {

    Point2D toScreen(GeoPoint p);

    GeoPoint toGeo(double x, double y);

    /** Ground distance covered by one screen pixel at the view centre, in metres. */
    double metersPerPixel();

    double zoom();

    double width();

    double height();
}
