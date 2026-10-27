package io.github.mustafagulturk.c2map.tools;

import io.github.mustafagulturk.c2map.geo.Geodesy;
import io.github.mustafagulturk.c2map.geo.WebMercator;
import io.github.mustafagulturk.c2map.map.TileKey;
import io.github.mustafagulturk.c2map.map.TileRange;
import io.github.mustafagulturk.c2map.map.TileStore;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import javax.imageio.ImageIO;

/**
 * Renders offline demo tiles in the OSM layout ({@code {out}/{z}/{x}/{y}.png}) from bundled Natural Earth
 * vector data (land, borders, lakes, rivers) and GeoNames populated places, in a muted dark style meant to
 * sit under the map's dark overlay. World tiles for zoom 0-3 are always produced; higher zooms only for
 * the bounding box.
 *
 * <pre>
 * DemoTileGenerator [--out DIR] [--bbox minLat,minLon,maxLat,maxLon] [--zoom 4-11] [--force]
 * </pre>
 */
public final class DemoTileGenerator {

    static final int WORLD_MAX_ZOOM = 3;
    static final int MAX_ZOOM = 14;
    private static final int TILE = WebMercator.TILE_SIZE;
    private static final String DATA = "/naturalearth/";

    private static final Color SEA = new Color(0x1d3443);
    private static final Color LAND = new Color(0x3a4540);
    private static final Color COAST = new Color(0x5b7783);
    private static final Color LAKE = new Color(0x22404f);
    private static final Color RIVER = new Color(0x2f5a6e);
    private static final Color BORDER = new Color(0x8d8499);
    private static final Color CITY = new Color(0xddd3bb);
    private static final Color LABEL = new Color(0xd6cebd);
    private static final Color HALO = new Color(0x172229);

    /** A polygon or line in zoom-0 world pixels with its bounding box. */
    private record Feature(List<double[]> parts, double minX, double minY, double maxX, double maxY) {

        /** Projects lon/lat parts; for {@code rings}, a ring that circles a pole is closed along that pole. */
        static Feature of(List<double[]> lonLatParts, boolean rings) {
            List<double[]> parts = new ArrayList<>(lonLatParts.size());
            double minX = Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            for (double[] ll : lonLatParts) {
                double[] p = new double[ll.length];
                double lon = ll.length > 0 ? ll[0] : 0;
                double latSum = 0;
                for (int i = 0; i < ll.length; i += 2) {
                    // Unwrap longitudes so rings crossing the antimeridian stay continuous (x may leave [0, 256]).
                    lon += Geodesy.normalizeLon(ll[i] - lon);
                    latSum += ll[i + 1];
                    p[i] = WebMercator.lonToX(lon, 0);
                    p[i + 1] = WebMercator.latToY(ll[i + 1], 0);
                    minX = Math.min(minX, p[i]);
                    maxX = Math.max(maxX, p[i]);
                    minY = Math.min(minY, p[i + 1]);
                    maxY = Math.max(maxY, p[i + 1]);
                }
                if (rings && p.length >= 4 && Math.abs(p[p.length - 2] - p[0]) > TILE * 0.99) {
                    // Ring around a pole (Antarctica): close it along the edge of the Mercator square.
                    double edge = latSum < 0 ? TILE : 0;
                    p = java.util.Arrays.copyOf(p, p.length + 4);
                    p[p.length - 4] = p[p.length - 6];
                    p[p.length - 3] = edge;
                    p[p.length - 2] = p[0];
                    p[p.length - 1] = edge;
                    minY = Math.min(minY, edge);
                    maxY = Math.max(maxY, edge);
                }
                parts.add(p);
            }
            return new Feature(parts, minX, minY, maxX, maxY);
        }

        boolean intersects(double x0, double y0, double x1, double y1) {
            return maxX >= x0 && minX <= x1 && maxY >= y0 && minY <= y1;
        }
    }

    /** A populated place (position in zoom-0 world pixels). */
    record City(String name, double x, double y, int population, boolean capital) {
    }

    /** A placed city label in world pixels of its zoom level. */
    private record Label(City city, Font font, double dotX, double dotY, double radius, double textX,
                         double baseline, Rectangle2D bounds) {
    }

    private final List<Feature> land = new ArrayList<>();
    private final List<Feature> lakes = new ArrayList<>();
    private final List<Feature> rivers = new ArrayList<>();
    private final List<Feature> borders = new ArrayList<>();
    private final List<City> cities = new ArrayList<>();
    private final Map<Integer, List<Label>> labels = new HashMap<>();

    /** Credit line shown on the map for these tiles (read back by the app from the tile directory). */
    static final String ATTRIBUTION = "Demo karolar: Natural Earth, GeoNames (CC BY)";

    public static void main(String[] args) throws IOException {
        System.setProperty("java.awt.headless", "true");
        TileArgs a;
        try {
            a = TileArgs.parse(args, Set.of("--out", "--bbox", "--zoom"), Set.of("--force", "--help"));
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            usage();
            System.exit(2);
            return;
        }
        if (a.flag("--help")) {
            usage();
            return;
        }
        Path out = a.outDir(TileStore.defaultRoot());
        double[] bbox = TileArgs.parseBbox(a.get("--bbox", TileArgs.DEFAULT_BBOX));
        int[] zoom = TileArgs.parseZoomRange(a.get("--zoom", "4-11"), MAX_ZOOM);

        long t0 = System.nanoTime();
        DemoTileGenerator gen = new DemoTileGenerator();
        gen.loadData();
        List<TileKey> keys = tileList(bbox, zoom[0], zoom[1]);
        int[] counts = gen.renderAll(keys, out, a.flag("--force"));
        Files.createDirectories(out);
        Files.writeString(out.resolve(TileStore.ATTRIBUTION_FILE), ATTRIBUTION + "\n");
        System.out.printf(Locale.ROOT, "%d tiles written, %d already present (of %d) in %s, %.1f s%n",
                counts[0], counts[1], keys.size(), out.toAbsolutePath(), (System.nanoTime() - t0) / 1e9);
    }

    private static void usage() {
        System.err.println("usage: DemoTileGenerator [--out DIR] [--bbox minLat,minLon,maxLat,maxLon]"
                + " [--zoom MIN-MAX] [--force]");
        System.err.println("  defaults: --out " + TileStore.defaultRoot() + " --bbox " + TileArgs.DEFAULT_BBOX
                + " --zoom 4-11; world tiles z0-" + WORLD_MAX_ZOOM + " are always included");
    }

    /** World tiles for z0-3 followed by the box tiles of the requested zoom range, without duplicates. */
    static List<TileKey> tileList(double[] bbox, int minZoom, int maxZoom) {
        Set<TileKey> keys = new LinkedHashSet<>();
        for (int z = 0; z <= WORLD_MAX_ZOOM; z++) {
            TileRange.world(z).forEach(keys::add);
        }
        for (TileRange r : TileRange.covering(bbox[0], bbox[1], bbox[2], bbox[3], minZoom, maxZoom)) {
            r.forEach(keys::add);
        }
        return new ArrayList<>(keys);
    }

    // ---------------------------------------------------------------- data

    void loadData() throws IOException {
        TopoJson countries = TopoJson.parse(readGz("countries-10m.topo.json.gz"));
        for (List<double[]> poly : countries.polygons("land")) {
            land.add(Feature.of(poly, true));
        }
        for (double[] arc : countries.sharedArcs("countries")) {
            borders.add(Feature.of(List.of(arc), false));
        }
        TopoJson water = TopoJson.parse(readGz("lakes-rivers-50m.topo.json.gz"));
        for (List<double[]> poly : water.polygons("lakes")) {
            lakes.add(Feature.of(poly, true));
        }
        for (double[] line : water.lines("rivers")) {
            rivers.add(Feature.of(List.of(line), false));
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(resource("cities15000.tsv.gz")), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                City c = parseCity(line);
                if (c != null) {
                    cities.add(c);
                }
            }
        }
        cities.sort((p, q) -> Integer.compare(q.population(), p.population()));
    }

    /** Parses "name\tlat\tlon\tpopulation\tfeatureCode"; null for malformed lines. */
    static City parseCity(String line) {
        String[] f = line.split("\t");
        if (f.length < 4) {
            return null;
        }
        try {
            double lat = Double.parseDouble(f[1]);
            double lon = Double.parseDouble(f[2]);
            int pop = Integer.parseInt(f[3]);
            boolean capital = f.length > 4 && f[4].equals("PPLC");
            return new City(f[0], WebMercator.lonToX(lon, 0), WebMercator.latToY(lat, 0), pop, capital);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static InputStream resource(String name) throws IOException {
        InputStream in = DemoTileGenerator.class.getResourceAsStream(DATA + name);
        if (in == null) {
            throw new IOException("missing resource " + DATA + name);
        }
        return in;
    }

    private static String readGz(String name) throws IOException {
        try (InputStream in = new GZIPInputStream(resource(name))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ---------------------------------------------------------------- labels

    /** Smallest population labelled at a zoom level. */
    static int populationThreshold(int z) {
        return switch (z) {
            case 0, 1 -> Integer.MAX_VALUE;
            case 2 -> 8_000_000;
            case 3 -> 3_000_000;
            case 4 -> 1_000_000;
            case 5 -> 500_000;
            case 6 -> 200_000;
            case 7 -> 100_000;
            case 8 -> 50_000;
            case 9 -> 25_000;
            default -> 15_000;
        };
    }

    /**
     * Places labels for one zoom level in world pixels, largest cities first, skipping any that would
     * overlap an earlier one. Done once per zoom so labels continue seamlessly across tile edges.
     */
    private List<Label> placeLabels(int z, Rectangle2D region) {
        int threshold = populationThreshold(z);
        double scale = Math.pow(2, z);
        BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scratch.createGraphics();
        applyHints(g);
        List<Label> placed = new ArrayList<>();
        for (City c : cities) {
            if (c.population() < threshold) {
                break; // sorted by population
            }
            double x = c.x() * scale;
            double y = c.y() * scale;
            if (!region.contains(x, y)) {
                continue;
            }
            Font font = labelFont(c, z);
            FontMetrics fm = g.getFontMetrics(font);
            double w = fm.stringWidth(c.name());
            double asc = fm.getAscent() * 0.8;
            double baseline = y + asc / 2;
            double r = dotRadius(c, z);
            Rectangle2D dot = new Rectangle2D.Double(x - r - 1, y - r - 1, 2 * r + 2, 2 * r + 2);
            for (int side = 0; side < 2; side++) {
                double tx = side == 0 ? x + r + 4 : x - r - 4 - w;
                Rectangle2D text = new Rectangle2D.Double(tx - 2, y - asc / 2 - 3, w + 4, asc + 6);
                if (free(placed, dot) && free(placed, text)) {
                    Rectangle2D bounds = text.createUnion(dot);
                    placed.add(new Label(c, font, x, y, r, tx, baseline, bounds));
                    break;
                }
            }
        }
        g.dispose();
        return placed;
    }

    private static boolean free(List<Label> placed, Rectangle2D r) {
        for (Label l : placed) {
            if (l.bounds().intersects(r)) {
                return false;
            }
        }
        return true;
    }

    private static Font labelFont(City c, int z) {
        float size = c.population() >= 1_000_000 ? 13f : c.population() >= 200_000 ? 12f : 11f;
        if (z <= 4) {
            size -= 1f;
        }
        int style = c.capital() || c.population() >= 1_000_000 ? Font.BOLD : Font.PLAIN;
        return new Font(Font.SANS_SERIF, style, 12).deriveFont(size);
    }

    private static double dotRadius(City c, int z) {
        return c.capital() || c.population() >= 1_000_000 ? 3.2 : z >= 9 ? 2.6 : 2.2;
    }

    // ---------------------------------------------------------------- rendering

    private int[] renderAll(List<TileKey> keys, Path out, boolean force) {
        Map<Integer, double[]> extent = new HashMap<>();
        for (TileKey k : keys) {
            extent.merge(k.z(), new double[] {k.x(), k.y(), k.x(), k.y()}, (p, q) -> new double[] {
                Math.min(p[0], q[0]), Math.min(p[1], q[1]), Math.max(p[2], q[2]), Math.max(p[3], q[3])});
        }
        extent.forEach((z, e) -> {
            double margin = TILE; // labels of cities just outside the area can reach into it
            Rectangle2D region = new Rectangle2D.Double(e[0] * TILE - margin, e[1] * TILE - margin,
                    (e[2] - e[0] + 1) * TILE + 2 * margin, (e[3] - e[1] + 1) * TILE + 2 * margin);
            labels.put(z, placeLabels(z, region));
        });

        AtomicInteger written = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        keys.parallelStream().forEach(k -> {
            Path file = TileStore.tilePath(out, k);
            if (!force && Files.exists(file)) {
                skipped.incrementAndGet();
                return;
            }
            try {
                Files.createDirectories(file.getParent());
                ImageIO.write(render(k), "png", file.toFile());
                written.incrementAndGet();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        return new int[] {written.get(), skipped.get()};
    }

    BufferedImage render(TileKey k) {
        BufferedImage img = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        applyHints(g);
        g.setColor(SEA);
        g.fillRect(0, 0, TILE, TILE);

        int z = k.z();
        double scale = Math.pow(2, z);
        double ox = (double) k.x() * TILE;
        double oy = (double) k.y() * TILE;
        double margin = 8 / scale;
        double x0 = ox / scale - margin;
        double y0 = oy / scale - margin;
        double x1 = (ox + TILE) / scale + margin;
        double y1 = (oy + TILE) / scale + margin;

        Path2D landPath = path(land, true, scale, ox, oy, x0, y0, x1, y1);
        g.setColor(LAND);
        g.fill(landPath);

        Path2D lakePath = path(lakes, true, scale, ox, oy, x0, y0, x1, y1);
        g.setColor(LAKE);
        g.fill(lakePath);

        if (z >= 4) {
            g.setColor(RIVER);
            g.setStroke(round(z >= 9 ? 1.4f : z >= 7 ? 1.1f : 0.8f));
            g.draw(path(rivers, false, scale, ox, oy, x0, y0, x1, y1));
        }

        g.setColor(COAST);
        g.setStroke(round(z >= 8 ? 1.2f : z >= 5 ? 0.9f : 0.6f));
        g.draw(landPath);
        g.draw(lakePath);

        g.setColor(BORDER);
        g.setStroke(new BasicStroke(z >= 8 ? 1.5f : z >= 5 ? 1.1f : 0.7f, BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND, 1f, new float[] {6f, 3f}, 0f));
        g.draw(path(borders, false, scale, ox, oy, x0, y0, x1, y1));

        drawLabels(g, labels.getOrDefault(z, List.of()), ox, oy);
        g.dispose();
        return img;
    }

    private static void drawLabels(Graphics2D g, List<Label> list, double ox, double oy) {
        Rectangle2D tile = new Rectangle2D.Double(ox, oy, TILE, TILE);
        BasicStroke halo = new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
        for (Label l : list) {
            if (!l.bounds().intersects(tile)) {
                continue;
            }
            double r = l.radius();
            Shape dot = new Ellipse2D.Double(l.dotX() - ox - r, l.dotY() - oy - r, 2 * r, 2 * r);
            g.setColor(HALO);
            g.setStroke(new BasicStroke(1.5f));
            g.draw(dot);
            g.setColor(CITY);
            g.fill(dot);
            Shape text = l.font().createGlyphVector(g.getFontRenderContext(), l.city().name())
                    .getOutline((float) (l.textX() - ox), (float) (l.baseline() - oy));
            g.setColor(HALO);
            g.setStroke(halo);
            g.draw(text);
            g.setColor(LABEL);
            g.fill(text);
        }
    }

    /**
     * Builds a tile-pixel path from the features overlapping the zoom-0 box, dropping vertices closer than
     * a third of a pixel to the previous one.
     */
    private static Path2D path(List<Feature> features, boolean closed, double scale, double ox, double oy,
                               double x0, double y0, double x1, double y1) {
        Path2D.Double p = new Path2D.Double(Path2D.WIND_EVEN_ODD);
        for (Feature f : features) {
            // Features unwrapped past the antimeridian are also drawn one world to the left or right.
            for (int shift = -TILE; shift <= TILE; shift += TILE) {
                if (f.intersects(x0 - shift, y0, x1 - shift, y1)) {
                    append(p, f, closed, scale, ox - shift * scale, oy);
                }
            }
        }
        return p;
    }

    private static void append(Path2D p, Feature f, boolean closed, double scale, double ox, double oy) {
        for (double[] part : f.parts()) {
            int n = part.length / 2;
            if (n < 2) {
                continue;
            }
            double lx = part[0] * scale - ox;
            double ly = part[1] * scale - oy;
            p.moveTo(lx, ly);
            for (int i = 1; i < n; i++) {
                double px = part[2 * i] * scale - ox;
                double py = part[2 * i + 1] * scale - oy;
                if (i < n - 1 && Math.abs(px - lx) + Math.abs(py - ly) < 0.33) {
                    continue;
                }
                p.lineTo(px, py);
                lx = px;
                ly = py;
            }
            if (closed) {
                p.closePath();
            }
        }
    }

    private static BasicStroke round(float width) {
        return new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    }

    private static void applyHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }
}
