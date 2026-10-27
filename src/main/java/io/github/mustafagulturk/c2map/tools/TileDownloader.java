package io.github.mustafagulturk.c2map.tools;

import io.github.mustafagulturk.c2map.map.TileKey;
import io.github.mustafagulturk.c2map.map.TileRange;
import io.github.mustafagulturk.c2map.map.TileStore;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Downloads raster tiles for a bounding box and zoom range into the offline layout
 * {@code {out}/{z}/{x}/{y}.png}, following the OSM tile usage policy
 * (https://operations.osmfoundation.org/policies/tiles/): identifying User-Agent, a single connection,
 * at most two requests per second, existing tiles are never fetched again, and large areas are refused
 * unless {@code --i-know} is given.
 *
 * <pre>
 * TileDownloader [--out DIR] [--bbox minLat,minLon,maxLat,maxLon] [--zoom 4-13]
 *                [--url https://tile.openstreetmap.org/{z}/{x}/{y}.png] [--i-know]
 * </pre>
 */
public final class TileDownloader {

    public static final String USER_AGENT = "c2-map-display/1.0 (+https://github.com/mustafagulturk/c2-map-display)";
    public static final String DEFAULT_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
    public static final String DEFAULT_ZOOM = "4-13";
    /** Above this many tiles the download needs {@code --i-know}. */
    public static final int MAX_TILES_WITHOUT_CONFIRMATION = 5000;
    /** Minimum spacing between request starts (2 requests per second). */
    public static final long MIN_REQUEST_INTERVAL_MS = 500;
    static final int MAX_ZOOM = 19;
    private static final int MAX_RETRIES = 3;

    private TileDownloader() {
    }

    public static void main(String[] args) throws InterruptedException {
        TileArgs a;
        try {
            a = TileArgs.parse(args, Set.of("--out", "--bbox", "--zoom", "--url"), Set.of("--i-know", "--help"));
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
        int[] zoom = TileArgs.parseZoomRange(a.get("--zoom", DEFAULT_ZOOM), MAX_ZOOM);
        String url = a.get("--url", DEFAULT_URL);
        validateTemplate(url);

        List<TileRange> ranges = TileRange.covering(bbox[0], bbox[1], bbox[2], bbox[3], zoom[0], zoom[1]);
        long total = TileRange.count(ranges);
        System.out.printf(Locale.ROOT, "%d tiles in zoom %d-%d for the box%n", total, zoom[0], zoom[1]);
        String refusal = refusal(total, a.flag("--i-know"));
        if (refusal != null) {
            System.err.println(refusal);
            System.exit(1);
            return;
        }
        List<TileKey> todo = missingTiles(ranges, out);
        System.out.printf(Locale.ROOT, "%d already present, %d to download into %s (~%d s at 2 req/s)%n",
                total - todo.size(), todo.size(), out.toAbsolutePath(),
                todo.size() * MIN_REQUEST_INTERVAL_MS / 1000);
        int[] result = download(todo, url, out);
        System.out.printf(Locale.ROOT, "done: %d downloaded, %d not available, %d failed%n",
                result[0], result[1], result[2]);
    }

    private static void usage() {
        System.err.println("usage: TileDownloader [--out DIR] [--bbox minLat,minLon,maxLat,maxLon] [--zoom MIN-MAX]"
                + " [--url TEMPLATE] [--i-know]");
        System.err.println("  defaults: --out " + TileStore.defaultRoot() + " --bbox " + TileArgs.DEFAULT_BBOX
                + " --zoom " + DEFAULT_ZOOM + " --url " + DEFAULT_URL);
        System.err.println("  Please respect the tile server's usage policy; bulk downloading is forbidden on"
                + " tile.openstreetmap.org.");
    }

    /** Error message when the request is too large, or null when it may proceed. */
    static String refusal(long tileCount, boolean iKnow) {
        if (tileCount > MAX_TILES_WITHOUT_CONFIRMATION && !iKnow) {
            return String.format(Locale.ROOT, "refusing to download %d tiles (more than %d); shrink the box or"
                    + " zoom range, or pass --i-know if your tile server permits it", tileCount,
                    MAX_TILES_WITHOUT_CONFIRMATION);
        }
        return null;
    }

    static void validateTemplate(String template) {
        if (!template.contains("{z}") || !template.contains("{x}") || !template.contains("{y}")) {
            throw new IllegalArgumentException("URL template needs {z}, {x} and {y}: " + template);
        }
        if (!template.startsWith("https://") && !template.startsWith("http://")) {
            throw new IllegalArgumentException("URL template must be http(s): " + template);
        }
    }

    /** Expands the template for one tile. */
    static String tileUrl(String template, TileKey k) {
        return template.replace("{z}", Integer.toString(k.z()))
                .replace("{x}", Integer.toString(k.x()))
                .replace("{y}", Integer.toString(k.y()));
    }

    /** Tiles of the ranges whose file does not exist yet, low zoom first. */
    static List<TileKey> missingTiles(List<TileRange> ranges, Path out) {
        List<TileKey> todo = new ArrayList<>();
        for (TileRange r : ranges) {
            r.forEach(k -> {
                if (!Files.exists(TileStore.tilePath(out, k))) {
                    todo.add(k);
                }
            });
        }
        return todo;
    }

    /** Sequential, rate-limited download; returns {downloaded, notFound, failed}. */
    private static int[] download(List<TileKey> todo, String template, Path out) throws InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        RateLimiter limiter = new RateLimiter(MIN_REQUEST_INTERVAL_MS, System::currentTimeMillis);
        int ok = 0;
        int notFound = 0;
        int failed = 0;
        for (int i = 0; i < todo.size(); i++) {
            TileKey k = todo.get(i);
            int status = -1;
            for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
                Thread.sleep(limiter.delayBeforeNext());
                status = fetch(client, template, k, out);
                if (status == 200 || status == 404) {
                    break;
                }
                if (status == 429 || status >= 500 || status < 0) {
                    Thread.sleep(5000L << attempt); // back off when the server is busy or unreachable
                } else {
                    break;
                }
            }
            if (status == 200) {
                ok++;
            } else if (status == 404) {
                notFound++;
            } else {
                failed++;
                System.err.println("failed " + k + " (HTTP " + status + ")");
                if (status == 403 || status == 418) {
                    System.err.println("the server refused access; stopping");
                    break;
                }
            }
            if ((i + 1) % 50 == 0) {
                System.out.printf(Locale.ROOT, "%d / %d%n", i + 1, todo.size());
            }
        }
        return new int[] {ok, notFound, failed};
    }

    private static int fetch(HttpClient client, String template, TileKey k, Path out) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(tileUrl(template, k)))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        try {
            HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 200) {
                Path file = TileStore.tilePath(out, k);
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".part");
                Files.write(tmp, resp.body());
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            return resp.statusCode();
        } catch (IOException e) {
            System.err.println(k + ": " + e.getMessage());
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /** Spaces request starts at least {@code intervalMs} apart. */
    static final class RateLimiter {
        private final long intervalMs;
        private final java.util.function.LongSupplier clock;
        private long next = Long.MIN_VALUE;

        RateLimiter(long intervalMs, java.util.function.LongSupplier clock) {
            this.intervalMs = intervalMs;
            this.clock = clock;
        }

        /** Milliseconds to wait before the next request may start; reserves that slot. */
        long delayBeforeNext() {
            long now = clock.getAsLong();
            long start = Math.max(now, next);
            next = start + intervalMs;
            return start - now;
        }
    }
}
