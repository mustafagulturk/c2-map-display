package io.github.mustafagulturk.c2map.map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TileCacheTest {

    /** Executor that queues tasks until the test runs them. */
    private static final class ManualExecutor implements Executor {
        final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable r) {
            tasks.add(r);
        }

        void runAll() {
            while (!tasks.isEmpty()) {
                tasks.poll().run();
            }
        }
    }

    private final Map<TileKey, Integer> loads = new ConcurrentHashMap<>();

    private Function<TileKey, String> loader(java.util.Set<TileKey> present) {
        return k -> {
            loads.merge(k, 1, Integer::sum);
            return present.contains(k) ? "tile " + k : null;
        };
    }

    @Test
    void loadsAsynchronouslyAndNotifies() {
        TileKey k = new TileKey(3, 1, 2);
        ManualExecutor ex = new ManualExecutor();
        TileCache<String> cache = new TileCache<>(4, loader(java.util.Set.of(k)), ex);
        AtomicInteger notified = new AtomicInteger();
        cache.setOnTileLoaded(notified::incrementAndGet);

        assertNull(cache.get(k));
        assertTrue(cache.isPending(k));
        assertNull(cache.get(k)); // no duplicate request while pending
        assertEquals(1, ex.tasks.size());

        ex.runAll();
        assertEquals("tile 3/1/2", cache.get(k));
        assertEquals("tile 3/1/2", cache.peek(k));
        assertEquals(1, notified.get());
        assertEquals(1, loads.get(k));
        assertFalse(cache.isPending(k));
    }

    @Test
    void remembersMissingTiles() {
        TileKey k = new TileKey(5, 5, 5);
        ManualExecutor ex = new ManualExecutor();
        TileCache<String> cache = new TileCache<>(4, loader(java.util.Set.of()), ex);
        AtomicInteger notified = new AtomicInteger();
        cache.setOnTileLoaded(notified::incrementAndGet);

        cache.get(k);
        ex.runAll();
        assertTrue(cache.isMissing(k));
        assertNull(cache.get(k));
        assertTrue(ex.tasks.isEmpty(), "missing tile must not be requested again");
        assertEquals(1, loads.get(k));
        assertEquals(0, notified.get());

        cache.clear();
        assertFalse(cache.isMissing(k));
        cache.get(k);
        assertEquals(1, ex.tasks.size());
    }

    @Test
    void loaderExceptionCountsAsMissing() {
        TileKey k = new TileKey(1, 0, 0);
        TileCache<String> cache = new TileCache<>(4, key -> {
            throw new UncheckedIOException(new IOException("corrupt"));
        }, Runnable::run);
        assertNull(cache.get(k));
        assertTrue(cache.isMissing(k));
    }

    @Test
    void evictsLeastRecentlyUsed() {
        TileKey a = new TileKey(2, 0, 0);
        TileKey b = new TileKey(2, 1, 0);
        TileKey c = new TileKey(2, 2, 0);
        TileCache<String> cache = new TileCache<>(2, loader(java.util.Set.of(a, b, c)), Runnable::run);
        cache.get(a);
        cache.get(b);
        cache.get(a); // touch a, so b is eldest
        cache.get(c);
        assertEquals(2, cache.size());
        assertEquals("tile 2/0/0", cache.peek(a));
        assertNull(cache.peek(b));
        assertEquals("tile 2/2/0", cache.peek(c));
    }

    @Test
    void peekNeverLoads() {
        TileKey k = new TileKey(4, 1, 1);
        ManualExecutor ex = new ManualExecutor();
        TileCache<String> cache = new TileCache<>(4, loader(java.util.Set.of(k)), ex);
        assertNull(cache.peek(k));
        assertTrue(ex.tasks.isEmpty());
    }

    @Test
    void fileLoaderReadsOsmLayoutAndToleratesMissingRoot(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("tiles");
        Function<TileKey, byte[]> files = TileStore.fileLoader(root, in -> {
            try {
                return in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        TileKey k = new TileKey(9, 294, 196);
        TileCache<byte[]> cache = new TileCache<>(8, files, Runnable::run);
        assertNull(cache.get(k)); // root does not exist yet
        assertTrue(cache.isMissing(k));

        Path p = TileStore.tilePath(root, new TileKey(9, 294, 197));
        assertEquals(root.resolve("9").resolve("294").resolve("197.png"), p);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "png", StandardCharsets.US_ASCII);
        TileKey present = new TileKey(9, 294, 197);
        assertNull(cache.get(present)); // first call only schedules the load
        assertArrayEquals("png".getBytes(StandardCharsets.US_ASCII), cache.get(present));
    }

    @Test
    void defaultRootHonoursSystemProperty() {
        String old = System.getProperty(TileStore.DIR_PROPERTY);
        try {
            System.setProperty(TileStore.DIR_PROPERTY, "/data/tiles");
            assertEquals(Path.of("/data/tiles"), TileStore.defaultRoot());
            System.clearProperty(TileStore.DIR_PROPERTY);
            if (System.getenv(TileStore.DIR_ENV) == null) {
                assertTrue(TileStore.defaultRoot().endsWith(Path.of(".c2map", "tiles")));
            }
        } finally {
            if (old == null) {
                System.clearProperty(TileStore.DIR_PROPERTY);
            } else {
                System.setProperty(TileStore.DIR_PROPERTY, old);
            }
        }
    }
}
