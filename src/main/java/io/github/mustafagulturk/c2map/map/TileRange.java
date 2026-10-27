package io.github.mustafagulturk.c2map.map;

import io.github.mustafagulturk.c2map.geo.WebMercator;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Inclusive rectangle of tiles at one zoom level, e.g. all tiles covering a lat/lon bounding box. */
public record TileRange(int z, int minX, int minY, int maxX, int maxY) {

    public TileRange {
        int n = 1 << z;
        if (minX < 0 || minY < 0 || maxX >= n || maxY >= n || minX > maxX || minY > maxY) {
            throw new IllegalArgumentException("invalid tile range at z" + z);
        }
    }

    /**
     * Tiles at zoom {@code z} covering the box. The box must not cross the antimeridian
     * ({@code minLon <= maxLon}); latitudes are clamped to the Mercator limit.
     */
    public static TileRange covering(double minLat, double minLon, double maxLat, double maxLon, int z) {
        if (minLat > maxLat || minLon > maxLon) {
            throw new IllegalArgumentException("min must not exceed max");
        }
        int x0 = WebMercator.tileX(minLon, z);
        int x1 = maxLon >= 180.0 ? (1 << z) - 1 : WebMercator.tileX(maxLon, z);
        int y0 = WebMercator.tileY(maxLat, z);
        int y1 = WebMercator.tileY(minLat, z);
        return new TileRange(z, x0, y0, x1, y1);
    }

    /** All tiles of zoom {@code z}. */
    public static TileRange world(int z) {
        int n = (1 << z) - 1;
        return new TileRange(z, 0, 0, n, n);
    }

    /** Ranges covering the box at each zoom of {@code [minZoom, maxZoom]}. */
    public static List<TileRange> covering(double minLat, double minLon, double maxLat, double maxLon,
                                           int minZoom, int maxZoom) {
        if (minZoom > maxZoom) {
            throw new IllegalArgumentException("minZoom > maxZoom");
        }
        List<TileRange> out = new ArrayList<>();
        for (int z = minZoom; z <= maxZoom; z++) {
            out.add(covering(minLat, minLon, maxLat, maxLon, z));
        }
        return out;
    }

    /** Total number of tiles in all ranges. */
    public static long count(List<TileRange> ranges) {
        return ranges.stream().mapToLong(TileRange::count).sum();
    }

    public long count() {
        return (long) (maxX - minX + 1) * (maxY - minY + 1);
    }

    public boolean contains(TileKey k) {
        return k.z() == z && k.x() >= minX && k.x() <= maxX && k.y() >= minY && k.y() <= maxY;
    }

    /** Visits every tile, row by row (x fastest). */
    public void forEach(Consumer<TileKey> action) {
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                action.accept(new TileKey(z, x, y));
            }
        }
    }
}
