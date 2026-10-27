package io.github.mustafagulturk.c2map.tools;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Command-line parsing shared by the tile tools: {@code --key value} options and boolean flags. */
final class TileArgs {

    /** Default area: Izmir and the central Aegean. */
    static final String DEFAULT_BBOX = "37.6,25.9,39.3,28.4";

    private final Map<String, String> values = new HashMap<>();
    private final Set<String> flags;

    private TileArgs(Set<String> flags) {
        this.flags = flags;
    }

    /**
     * @param args      raw arguments
     * @param withValue option names that take a value (e.g. "--out")
     * @param booleans  option names that are plain flags (e.g. "--force")
     */
    static TileArgs parse(String[] args, Set<String> withValue, Set<String> booleans) {
        TileArgs a = new TileArgs(new java.util.HashSet<>());
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            int eq = arg.indexOf('=');
            String name = eq > 0 ? arg.substring(0, eq) : arg;
            if (booleans.contains(name) && eq < 0) {
                a.flags.add(name);
            } else if (withValue.contains(name)) {
                if (eq > 0) {
                    a.values.put(name, arg.substring(eq + 1));
                } else if (i + 1 < args.length) {
                    a.values.put(name, args[++i]);
                } else {
                    throw new IllegalArgumentException("missing value for " + name);
                }
            } else {
                throw new IllegalArgumentException("unknown option: " + arg);
            }
        }
        return a;
    }

    boolean flag(String name) {
        return flags.contains(name);
    }

    String get(String name, String def) {
        return values.getOrDefault(name, def);
    }

    Path outDir(Path def) {
        String v = values.get("--out");
        if (v == null) {
            return def;
        }
        if (v.startsWith("~/")) {
            return Path.of(System.getProperty("user.home")).resolve(v.substring(2));
        }
        return Path.of(v);
    }

    /** "minLat,minLon,maxLat,maxLon" to {minLat, minLon, maxLat, maxLon}. */
    static double[] parseBbox(String s) {
        String[] parts = s.split(",");
        if (parts.length != 4) {
            throw new IllegalArgumentException("bbox must be minLat,minLon,maxLat,maxLon: " + s);
        }
        double[] b = new double[4];
        for (int i = 0; i < 4; i++) {
            b[i] = Double.parseDouble(parts[i].trim());
        }
        if (b[0] > b[2] || b[1] > b[3] || b[0] < -90 || b[2] > 90 || b[1] < -180 || b[3] > 180) {
            throw new IllegalArgumentException("invalid bbox: " + s);
        }
        return b;
    }

    /** "4-11" or "9" to {min, max}. */
    static int[] parseZoomRange(String s, int maxZoom) {
        String[] parts = s.trim().split("-");
        int lo = Integer.parseInt(parts[0].trim());
        int hi = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : lo;
        if (parts.length > 2 || lo < 0 || hi > maxZoom || lo > hi) {
            throw new IllegalArgumentException("zoom range must be within 0-" + maxZoom + ": " + s);
        }
        return new int[] {lo, hi};
    }
}
