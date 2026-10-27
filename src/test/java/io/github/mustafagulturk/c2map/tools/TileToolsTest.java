package io.github.mustafagulturk.c2map.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mustafagulturk.c2map.map.TileKey;
import io.github.mustafagulturk.c2map.map.TileRange;
import io.github.mustafagulturk.c2map.map.TileStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TileToolsTest {

    @Test
    void parsesOptions() {
        TileArgs a = TileArgs.parse(new String[] {"--out", "/x", "--zoom=5-7", "--force"},
                Set.of("--out", "--zoom"), Set.of("--force"));
        assertEquals(Path.of("/x"), a.outDir(Path.of("/def")));
        assertEquals("5-7", a.get("--zoom", "?"));
        assertTrue(a.flag("--force"));
        assertThrows(IllegalArgumentException.class,
                () -> TileArgs.parse(new String[] {"--bogus"}, Set.of(), Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> TileArgs.parse(new String[] {"--out"}, Set.of("--out"), Set.of()));
    }

    @Test
    void parsesBboxAndZoom() {
        assertArrayEquals(new double[] {37.6, 25.9, 39.3, 28.4}, TileArgs.parseBbox(TileArgs.DEFAULT_BBOX));
        assertThrows(IllegalArgumentException.class, () -> TileArgs.parseBbox("39,25,38,28"));
        assertThrows(IllegalArgumentException.class, () -> TileArgs.parseBbox("1,2,3"));
        assertArrayEquals(new int[] {4, 11}, TileArgs.parseZoomRange("4-11", 14));
        assertArrayEquals(new int[] {9, 9}, TileArgs.parseZoomRange("9", 14));
        assertThrows(IllegalArgumentException.class, () -> TileArgs.parseZoomRange("11-4", 14));
        assertThrows(IllegalArgumentException.class, () -> TileArgs.parseZoomRange("4-20", 19));
    }

    @Test
    void demoTileListHasWorldPlusBoxWithoutDuplicates() {
        double[] box = TileArgs.parseBbox(TileArgs.DEFAULT_BBOX);
        List<TileKey> keys = DemoTileGenerator.tileList(box, 2, 5);
        long boxTiles = TileRange.count(TileRange.covering(box[0], box[1], box[2], box[3], 4, 5));
        assertEquals(85 + boxTiles, keys.size());
        assertEquals(keys.size(), keys.stream().distinct().count());
        assertEquals(new TileKey(0, 0, 0), keys.get(0));
    }

    @Test
    void downloaderRefusesLargeRequests() {
        assertNull(TileDownloader.refusal(5000, false));
        assertNotNull(TileDownloader.refusal(5001, false));
        assertNull(TileDownloader.refusal(1_000_000, true));
        // The default Izmir box up to z13 stays below the limit, z14 does not.
        double[] b = TileArgs.parseBbox(TileArgs.DEFAULT_BBOX);
        long z13 = TileRange.count(TileRange.covering(b[0], b[1], b[2], b[3], 4, 13));
        long z14 = TileRange.count(TileRange.covering(b[0], b[1], b[2], b[3], 4, 14));
        assertTrue(z13 <= TileDownloader.MAX_TILES_WITHOUT_CONFIRMATION, "z13: " + z13);
        assertTrue(z14 > TileDownloader.MAX_TILES_WITHOUT_CONFIRMATION, "z14: " + z14);
    }

    @Test
    void downloaderSkipsExistingTiles(@TempDir Path dir) throws IOException {
        List<TileRange> ranges = List.of(new TileRange(9, 294, 196, 295, 196), new TileRange(10, 588, 392, 588, 392));
        Path existing = TileStore.tilePath(dir, new TileKey(9, 295, 196));
        Files.createDirectories(existing.getParent());
        Files.write(existing, new byte[] {1});
        assertEquals(List.of(new TileKey(9, 294, 196), new TileKey(10, 588, 392)),
                TileDownloader.missingTiles(ranges, dir));
    }

    @Test
    void urlTemplate() {
        assertEquals("https://tile.openstreetmap.org/9/294/196.png",
                TileDownloader.tileUrl(TileDownloader.DEFAULT_URL, new TileKey(9, 294, 196)));
        TileDownloader.validateTemplate(TileDownloader.DEFAULT_URL);
        assertThrows(IllegalArgumentException.class,
                () -> TileDownloader.validateTemplate("https://a/{z}/{x}.png"));
        assertThrows(IllegalArgumentException.class,
                () -> TileDownloader.validateTemplate("file:///{z}/{x}/{y}.png"));
    }

    @Test
    void rateLimiterAllowsTwoRequestsPerSecond() {
        AtomicLong now = new AtomicLong(1000);
        TileDownloader.RateLimiter rl = new TileDownloader.RateLimiter(500, now::get);
        assertEquals(0, rl.delayBeforeNext());
        assertEquals(500, rl.delayBeforeNext()); // immediately again: wait for the next slot
        now.set(1600);
        assertEquals(400, rl.delayBeforeNext()); // slots at 1000, 1500, 2000
        now.set(5000);
        assertEquals(0, rl.delayBeforeNext());
    }
}
