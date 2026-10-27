package io.github.mustafagulturk.c2map.map;

/** Address of one slippy-map tile: zoom level, column and row (OSM / "XYZ" scheme, y down). */
public record TileKey(int z, int x, int y) {

    /** Highest zoom level accepted. */
    public static final int MAX_ZOOM = 24;

    public TileKey {
        if (z < 0 || z > MAX_ZOOM) {
            throw new IllegalArgumentException("zoom out of range: " + z);
        }
        int n = 1 << z;
        if (x < 0 || x >= n || y < 0 || y >= n) {
            throw new IllegalArgumentException("tile out of range: " + z + "/" + x + "/" + y);
        }
    }

    /** The tile one level up that contains this one; zoom 0 is its own parent. */
    public TileKey parent() {
        return z == 0 ? this : new TileKey(z - 1, x >> 1, y >> 1);
    }

    /** Number of tiles per axis at this zoom. */
    public int tilesPerAxis() {
        return 1 << z;
    }

    /** Relative path in the standard layout, {@code z/x/y.png}, with forward slashes. */
    public String path() {
        return z + "/" + x + "/" + y + ".png";
    }

    @Override
    public String toString() {
        return z + "/" + x + "/" + y;
    }
}
