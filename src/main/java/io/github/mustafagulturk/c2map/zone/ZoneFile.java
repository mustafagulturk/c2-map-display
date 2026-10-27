package io.github.mustafagulturk.c2map.zone;

import io.github.mustafagulturk.c2map.geo.GeoPoint;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Saves and loads zones as a small UTF-8 text file so they survive restarts.
 *
 * <pre>
 * C2ZONES 1
 * zone&lt;TAB&gt;id&lt;TAB&gt;createdAtMs&lt;TAB&gt;name
 * v&lt;TAB&gt;lat&lt;TAB&gt;lon
 * v&lt;TAB&gt;lat&lt;TAB&gt;lon
 * ...
 * </pre>
 * Each {@code zone} line is followed by its vertices. Blank lines and lines starting with
 * {@code #} are ignored. Tabs and line breaks in names are replaced by spaces when saving.
 */
public final class ZoneFile {

    static final String HEADER = "C2ZONES 1";

    private ZoneFile() {
    }

    /** Writes the zones, replacing the file atomically where the file system allows. */
    public static void save(Path file, List<RestrictedZone> zones) throws IOException {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (RestrictedZone z : zones) {
            sb.append("zone\t").append(clean(z.id())).append('\t').append(z.createdAtMs())
                    .append('\t').append(clean(z.name())).append('\n');
            for (GeoPoint v : z.vertices()) {
                sb.append(String.format(Locale.ROOT, "v\t%.7f\t%.7f%n", v.lat(), v.lon()));
            }
        }
        Path abs = file.toAbsolutePath();
        Path dir = abs.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path tmp = abs.resolveSibling(abs.getFileName() + ".tmp");
        Files.writeString(tmp, sb, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Reads zones written by {@link #save}. A missing file yields an empty list.
     *
     * @throws IOException on read errors or malformed content (message names the line)
     */
    public static List<RestrictedZone> load(Path file) throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).strip().equals(HEADER)) {
            throw new IOException("not a zone file: " + file);
        }
        List<RestrictedZone> out = new ArrayList<>();
        String[] head = null;
        List<GeoPoint> verts = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            try {
                if (f[0].equals("zone") && f.length == 4) {
                    if (head != null) {
                        out.add(build(head, verts));
                    }
                    head = f;
                    verts = new ArrayList<>();
                } else if (f[0].equals("v") && f.length == 3 && head != null) {
                    verts.add(new GeoPoint(Double.parseDouble(f[1]), Double.parseDouble(f[2])));
                } else {
                    throw new IllegalArgumentException("unexpected content");
                }
            } catch (IllegalArgumentException e) {
                throw new IOException(file + " line " + (i + 1) + ": " + e.getMessage(), e);
            }
        }
        if (head != null) {
            try {
                out.add(build(head, verts));
            } catch (IllegalArgumentException e) {
                throw new IOException(file + ": " + e.getMessage(), e);
            }
        }
        return out;
    }

    private static RestrictedZone build(String[] head, List<GeoPoint> verts) {
        return new RestrictedZone(head[1], head[3], verts, Long.parseLong(head[2]));
    }

    private static String clean(String s) {
        return s.replaceAll("[\\t\\r\\n]", " ");
    }
}
