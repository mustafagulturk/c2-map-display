package io.github.mustafagulturk.c2map.zone;

import io.github.mustafagulturk.c2map.geo.GeoPoint;
import io.github.mustafagulturk.c2map.geo.Geodesy;
import java.util.List;
import java.util.Objects;

/**
 * A restricted (no-go) area drawn by the operator: a simple polygon, convex or concave.
 *
 * <p>Geometry is evaluated in a local east/north plane around the vertex mean
 * ({@link Geodesy#toEastNorth}), which is accurate for zones up to a few hundred kilometres across
 * and also works across the antimeridian. Points exactly on the border may test either way.
 *
 * @param id          stable unique id
 * @param name        operator-facing name, e.g. "Bölge-1"
 * @param vertices    polygon corners in drawing order (at least 3; the ring is closed implicitly)
 * @param createdAtMs creation time, epoch ms
 */
public record RestrictedZone(String id, String name, List<GeoPoint> vertices, long createdAtMs) {

    /** Points further than this from the zone's reference point are rejected without a polygon test. */
    private static final double MAX_PLANE_RADIUS_M = 2_000_000;

    public RestrictedZone {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        if (id.isBlank()) {
            throw new IllegalArgumentException("blank zone id");
        }
        vertices = List.copyOf(vertices);
        if (vertices.size() < 3) {
            throw new IllegalArgumentException("a zone needs at least 3 vertices, got " + vertices.size());
        }
    }

    public RestrictedZone withName(String newName) {
        return new RestrictedZone(id, newName, vertices, createdAtMs);
    }

    /** Reference point of the local plane: the mean of the vertices (antimeridian safe). */
    public GeoPoint referencePoint() {
        GeoPoint first = vertices.get(0);
        double e = 0;
        double n = 0;
        for (GeoPoint v : vertices) {
            double[] en = Geodesy.toEastNorth(first, v);
            e += en[0];
            n += en[1];
        }
        return Geodesy.fromEastNorth(first, e / vertices.size(), n / vertices.size());
    }

    /** True if {@code p} lies inside the polygon (even-odd rule, ray casting). */
    public boolean contains(GeoPoint p) {
        GeoPoint origin = referencePoint();
        double[] q = Geodesy.toEastNorth(origin, p);
        double qx = q[0];
        double qy = q[1];
        if (Math.hypot(qx, qy) > MAX_PLANE_RADIUS_M) {
            return false;
        }
        double[][] ring = project(origin);
        boolean inside = false;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            double xi = ring[i][0];
            double yi = ring[i][1];
            double xj = ring[j][0];
            double yj = ring[j][1];
            // Half-open rule on y avoids double-counting a ray that passes exactly through a vertex.
            if ((yi > qy) != (yj > qy)) {
                double xCross = xi + (qy - yi) * (xj - xi) / (yj - yi);
                if (qx < xCross) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }

    /** Enclosed area in square metres (absolute value, independent of vertex order). */
    public double areaM2() {
        return Math.abs(signedArea(project(referencePoint())));
    }

    /**
     * Area-weighted centroid of the polygon; falls back to {@link #referencePoint()} for degenerate
     * (zero-area) polygons. For strongly concave shapes the centroid may lie outside the polygon.
     */
    public GeoPoint centroid() {
        GeoPoint origin = referencePoint();
        double[][] ring = project(origin);
        double a = signedArea(ring);
        if (Math.abs(a) < 1e-6) {
            return origin;
        }
        double cx = 0;
        double cy = 0;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            double cross = ring[j][0] * ring[i][1] - ring[i][0] * ring[j][1];
            cx += (ring[j][0] + ring[i][0]) * cross;
            cy += (ring[j][1] + ring[i][1]) * cross;
        }
        return Geodesy.fromEastNorth(origin, cx / (6 * a), cy / (6 * a));
    }

    private double[][] project(GeoPoint origin) {
        double[][] ring = new double[vertices.size()][];
        for (int i = 0; i < ring.length; i++) {
            ring[i] = Geodesy.toEastNorth(origin, vertices.get(i));
        }
        return ring;
    }

    private static double signedArea(double[][] ring) {
        double s = 0;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            s += ring[j][0] * ring[i][1] - ring[i][0] * ring[j][1];
        }
        return s / 2;
    }
}
