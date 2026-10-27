package io.github.mustafagulturk.c2map.map;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javafx.scene.image.Image;

/**
 * Offline tile source reading the standard OSM layout {@code {root}/{z}/{x}/{y}.png}. Tiles are decoded
 * on a small daemon thread pool (newest request first) and kept in a {@link TileCache}. A missing or
 * unreadable root directory simply yields no tiles.
 */
public final class TileStore implements AutoCloseable {

    /** System property overriding the default tile directory. */
    public static final String DIR_PROPERTY = "c2map.tiles";

    /** Environment variable overriding the default tile directory. */
    public static final String DIR_ENV = "C2MAP_TILES";

    private static final int THREADS = 2;

    private final Path root;
    private final ExecutorService executor;
    private final TileCache<Image> cache;

    /** Store on {@link #defaultRoot()}. */
    public TileStore() {
        this(defaultRoot());
    }

    public TileStore(Path root) {
        this(root, TileCache.DEFAULT_CAPACITY);
    }

    public TileStore(Path root, int capacity) {
        this.root = root;
        this.executor = newLifoPool(THREADS);
        this.cache = new TileCache<>(capacity, fileLoader(root, TileStore::decode), executor);
    }

    /** {@code -Dc2map.tiles}, else {@code $C2MAP_TILES}, else {@code ~/.c2map/tiles}. */
    public static Path defaultRoot() {
        String dir = System.getProperty(DIR_PROPERTY);
        if (dir == null || dir.isBlank()) {
            dir = System.getenv(DIR_ENV);
        }
        if (dir != null && !dir.isBlank()) {
            return Path.of(dir);
        }
        return Path.of(System.getProperty("user.home"), ".c2map", "tiles");
    }

    /** File of a tile below {@code root}. */
    public static Path tilePath(Path root, TileKey key) {
        return root.resolve(Integer.toString(key.z()))
                .resolve(Integer.toString(key.x()))
                .resolve(key.y() + ".png");
    }

    /** Loader for {@link TileCache}: null when the file is absent, otherwise the decoded file. */
    static <T> Function<TileKey, T> fileLoader(Path root, Function<InputStream, T> decoder) {
        return key -> {
            Path p = tilePath(root, key);
            if (!Files.isRegularFile(p)) {
                return null;
            }
            try (InputStream in = Files.newInputStream(p)) {
                return decoder.apply(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    private static Image decode(InputStream in) {
        Image img = new Image(in);
        return img.isError() || img.getWidth() <= 0 ? null : img;
    }

    private static ExecutorService newLifoPool(int threads) {
        AtomicInteger n = new AtomicInteger();
        // A LIFO queue loads the tiles the user is looking at now before stale requests from a fast pan.
        LinkedBlockingDeque<Runnable> queue = new LinkedBlockingDeque<>() {
            private static final long serialVersionUID = 1L;

            @Override
            public boolean offer(Runnable r) {
                return super.offerFirst(r);
            }
        };
        return new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, queue, r -> {
            Thread t = new Thread(r, "tile-loader-" + n.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
    }

    public Path root() {
        return root;
    }

    /** Cached tile, or null after scheduling an asynchronous load. */
    public Image get(TileKey key) {
        return cache.get(key);
    }

    /** Cached tile or null; never loads. */
    public Image peek(TileKey key) {
        return cache.peek(key);
    }

    public boolean isMissing(TileKey key) {
        return cache.isMissing(key);
    }

    /** Callback run on a loader thread whenever a tile arrives; use it to request a repaint. */
    public void setOnTileLoaded(Runnable callback) {
        cache.setOnTileLoaded(callback);
    }

    /** Drops all cached and remembered-missing tiles, e.g. after downloading new ones. */
    public void clear() {
        cache.clear();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
