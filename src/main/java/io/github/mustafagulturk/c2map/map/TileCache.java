package io.github.mustafagulturk.c2map.map;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;

/**
 * Bounded LRU cache of tiles with asynchronous loading, independent of the tile type so it can be
 * tested without JavaFX. Tiles the loader reports as absent are remembered (in a bounded LRU set) and
 * not requested again until {@link #clear()}.
 *
 * @param <T> decoded tile type
 */
public final class TileCache<T> {

    /** Default number of decoded tiles kept in memory. */
    public static final int DEFAULT_CAPACITY = 512;

    private static final int MISSING_CAPACITY = 16_384;

    private final Function<TileKey, T> loader;
    private final Executor executor;
    private final Map<TileKey, T> tiles;
    private final Map<TileKey, Boolean> missing;
    private final Set<TileKey> pending = new HashSet<>();
    private volatile Runnable onTileLoaded = () -> { };

    /**
     * @param capacity maximum number of tiles kept
     * @param loader   loads one tile synchronously; returns null (or throws) when the tile does not exist
     * @param executor runs the loads
     */
    public TileCache(int capacity, Function<TileKey, T> loader, Executor executor) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.loader = loader;
        this.executor = executor;
        this.tiles = lru(capacity);
        this.missing = lru(MISSING_CAPACITY);
    }

    private static <K, V> Map<K, V> lru(int capacity) {
        return new LinkedHashMap<>(Math.min(capacity, 1024), 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > capacity;
            }
        };
    }

    /** Called (on a loader thread) each time a tile has been loaded into the cache. */
    public void setOnTileLoaded(Runnable callback) {
        onTileLoaded = callback == null ? () -> { } : callback;
    }

    /**
     * Returns the cached tile, or null after scheduling a load (unless one is already pending or the
     * tile is known to be missing).
     */
    public T get(TileKey key) {
        synchronized (this) {
            T t = tiles.get(key);
            if (t != null || missing.containsKey(key) || !pending.add(key)) {
                return t;
            }
        }
        try {
            executor.execute(() -> load(key));
        } catch (RejectedExecutionException e) {
            synchronized (this) {
                pending.remove(key);
            }
        }
        return null;
    }

    /** Returns the cached tile without triggering a load. */
    public synchronized T peek(TileKey key) {
        return tiles.get(key);
    }

    /** True if the loader reported this tile as absent. */
    public synchronized boolean isMissing(TileKey key) {
        return missing.containsKey(key);
    }

    public synchronized boolean isPending(TileKey key) {
        return pending.contains(key);
    }

    public synchronized int size() {
        return tiles.size();
    }

    /** Forgets all cached and missing tiles (e.g. after new tiles were written to disk). */
    public synchronized void clear() {
        tiles.clear();
        missing.clear();
    }

    private void load(TileKey key) {
        T t;
        try {
            t = loader.apply(key);
        } catch (RuntimeException e) {
            t = null;
        }
        synchronized (this) {
            pending.remove(key);
            if (t == null) {
                missing.put(key, Boolean.TRUE);
                return;
            }
            tiles.put(key, t);
        }
        onTileLoaded.run();
    }
}
