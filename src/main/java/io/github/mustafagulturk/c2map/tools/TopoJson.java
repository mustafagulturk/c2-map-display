package io.github.mustafagulturk.c2map.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Small TopoJSON decoder: turns the shared, optionally quantised arcs of a topology back into
 * coordinate rings and lines. Coordinates are returned interleaved as {@code [lon0, lat0, lon1, lat1, ...]}.
 */
final class TopoJson {

    private final double[][] arcs;
    private final Map<?, ?> objects;

    private TopoJson(double[][] arcs, Map<?, ?> objects) {
        this.arcs = arcs;
        this.objects = objects;
    }

    static TopoJson parse(String json) {
        Map<?, ?> root = asMap(MiniJson.parse(json));
        if (!"Topology".equals(root.get("type"))) {
            throw new IllegalArgumentException("not a TopoJSON topology");
        }
        double sx = 1;
        double sy = 1;
        double tx = 0;
        double ty = 0;
        boolean quantised = false;
        if (root.get("transform") instanceof Map<?, ?> t) {
            List<?> scale = asList(t.get("scale"));
            List<?> translate = asList(t.get("translate"));
            sx = num(scale.get(0));
            sy = num(scale.get(1));
            tx = num(translate.get(0));
            ty = num(translate.get(1));
            quantised = true;
        }
        List<?> rawArcs = asList(root.get("arcs"));
        double[][] arcs = new double[rawArcs.size()][];
        for (int i = 0; i < arcs.length; i++) {
            List<?> pts = asList(rawArcs.get(i));
            double[] out = new double[pts.size() * 2];
            double x = 0;
            double y = 0;
            for (int j = 0; j < pts.size(); j++) {
                List<?> p = asList(pts.get(j));
                if (quantised) {
                    x += num(p.get(0));
                    y += num(p.get(1));
                    out[2 * j] = x * sx + tx;
                    out[2 * j + 1] = y * sy + ty;
                } else {
                    out[2 * j] = num(p.get(0));
                    out[2 * j + 1] = num(p.get(1));
                }
            }
            arcs[i] = out;
        }
        return new TopoJson(arcs, asMap(root.get("objects")));
    }

    Set<String> objectNames() {
        Set<String> names = new HashSet<>();
        for (Object k : objects.keySet()) {
            names.add(String.valueOf(k));
        }
        return names;
    }

    /** Polygons of an object (Polygon / MultiPolygon geometries); each polygon is a list of rings. */
    List<List<double[]>> polygons(String object) {
        List<List<double[]>> out = new ArrayList<>();
        for (Map<?, ?> g : geometries(object)) {
            Object type = g.get("type");
            if ("Polygon".equals(type)) {
                out.add(rings(asList(g.get("arcs"))));
            } else if ("MultiPolygon".equals(type)) {
                for (Object poly : asList(g.get("arcs"))) {
                    out.add(rings(asList(poly)));
                }
            }
        }
        return out;
    }

    /** Lines of an object (LineString / MultiLineString geometries). */
    List<double[]> lines(String object) {
        List<double[]> out = new ArrayList<>();
        for (Map<?, ?> g : geometries(object)) {
            Object type = g.get("type");
            if ("LineString".equals(type)) {
                out.add(stitch(asList(g.get("arcs"))));
            } else if ("MultiLineString".equals(type)) {
                for (Object line : asList(g.get("arcs"))) {
                    out.add(stitch(asList(line)));
                }
            }
        }
        return out;
    }

    /**
     * Arcs referenced by at least two different geometries of the object, e.g. the land borders
     * between countries (coastlines belong to one country only).
     */
    List<double[]> sharedArcs(String object) {
        Map<Integer, Integer> users = new HashMap<>();
        for (Map<?, ?> g : geometries(object)) {
            Set<Integer> mine = new HashSet<>();
            collectArcIndices(g.get("arcs"), mine);
            for (Integer i : mine) {
                users.merge(i, 1, Integer::sum);
            }
        }
        List<double[]> out = new ArrayList<>();
        users.forEach((i, n) -> {
            if (n >= 2) {
                out.add(arcs[i]);
            }
        });
        return out;
    }

    private static void collectArcIndices(Object node, Set<Integer> into) {
        if (node instanceof List<?> list) {
            for (Object o : list) {
                collectArcIndices(o, into);
            }
        } else if (node != null) {
            int i = (int) num(node);
            into.add(i >= 0 ? i : ~i);
        }
    }

    private List<double[]> rings(List<?> ringArcs) {
        List<double[]> rings = new ArrayList<>();
        for (Object r : ringArcs) {
            rings.add(stitch(asList(r)));
        }
        return rings;
    }

    /** Joins arcs (negative index = reversed) into one coordinate sequence, dropping shared end points. */
    double[] stitch(List<?> arcIndices) {
        int total = 0;
        for (Object o : arcIndices) {
            int i = (int) num(o);
            total += arcs[i >= 0 ? i : ~i].length;
        }
        double[] out = new double[total];
        int n = 0;
        for (Object o : arcIndices) {
            int i = (int) num(o);
            double[] a = arcs[i >= 0 ? i : ~i];
            int pts = a.length / 2;
            for (int k = 0; k < pts; k++) {
                int src = i >= 0 ? k : pts - 1 - k;
                if (k == 0 && n > 0) {
                    continue; // same as the previous arc's last point
                }
                out[n++] = a[2 * src];
                out[n++] = a[2 * src + 1];
            }
        }
        return n == out.length ? out : java.util.Arrays.copyOf(out, n);
    }

    private List<Map<?, ?>> geometries(String object) {
        Object o = objects.get(object);
        if (o == null) {
            throw new IllegalArgumentException("no object '" + object + "'");
        }
        Map<?, ?> obj = asMap(o);
        List<Map<?, ?>> out = new ArrayList<>();
        if ("GeometryCollection".equals(obj.get("type"))) {
            for (Object g : asList(obj.get("geometries"))) {
                out.add(asMap(g));
            }
        } else {
            out.add(obj);
        }
        return out;
    }

    private static Map<?, ?> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            return m;
        }
        throw new IllegalArgumentException("expected JSON object");
    }

    private static List<?> asList(Object o) {
        if (o instanceof List<?> l) {
            return l;
        }
        throw new IllegalArgumentException("expected JSON array");
    }

    private static double num(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        throw new IllegalArgumentException("expected number");
    }
}
