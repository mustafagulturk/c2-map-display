package io.github.mustafagulturk.c2map.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TileRangeTest {

    @Test
    void tileKeyValidationAndParent() {
        TileKey k = new TileKey(9, 294, 196);
        assertEquals(new TileKey(8, 147, 98), k.parent());
        assertEquals(new TileKey(0, 0, 0), new TileKey(0, 0, 0).parent());
        assertEquals("9/294/196.png", k.path());
        assertThrows(IllegalArgumentException.class, () -> new TileKey(2, 4, 0));
        assertThrows(IllegalArgumentException.class, () -> new TileKey(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new TileKey(3, 0, -1));
    }

    @Test
    void worldCounts() {
        assertEquals(1, TileRange.world(0).count());
        assertEquals(64, TileRange.world(3).count());
        long z0to3 = 0;
        for (int z = 0; z <= 3; z++) {
            z0to3 += TileRange.world(z).count();
        }
        assertEquals(85, z0to3);
    }

    @Test
    void izmirBoxAtZoomNine() {
        // 37.6..39.3 N, 25.9..28.4 E: x 292..296, y 195..198 at z9.
        TileRange r = TileRange.covering(37.6, 25.9, 39.3, 28.4, 9);
        assertEquals(new TileRange(9, 292, 195, 296, 198), r);
        assertEquals(20, r.count());
        assertTrue(r.contains(new TileKey(9, 294, 196)));
        assertFalse(r.contains(new TileKey(9, 291, 196)));
        assertFalse(r.contains(new TileKey(8, 147, 98)));
    }

    @Test
    void singlePointGivesOneTilePerZoom() {
        List<TileRange> ranges = TileRange.covering(38.42, 27.14, 38.42, 27.14, 0, 18);
        assertEquals(19, ranges.size());
        assertEquals(19, TileRange.count(ranges));
    }

    @Test
    void countsGrowRoughlyFourfoldPerZoom() {
        List<TileRange> ranges = TileRange.covering(37.6, 25.9, 39.3, 28.4, 4, 11);
        long total = TileRange.count(ranges);
        long z11 = ranges.get(ranges.size() - 1).count();
        long z10 = ranges.get(ranges.size() - 2).count();
        assertTrue(z11 > 3 * z10 && z11 < 5 * z10, z10 + " -> " + z11);
        long sum = 0;
        for (TileRange r : ranges) {
            sum += r.count();
        }
        assertEquals(sum, total);
    }

    @Test
    void forEachVisitsEveryTileOnce() {
        TileRange r = new TileRange(5, 3, 7, 5, 8);
        List<TileKey> keys = new ArrayList<>();
        r.forEach(keys::add);
        assertEquals(6, keys.size());
        assertEquals(new TileKey(5, 3, 7), keys.get(0));
        assertEquals(new TileKey(5, 5, 8), keys.get(5));
        assertEquals(6, keys.stream().distinct().count());
    }

    @Test
    void eastEdgeAndPolarBoxesStayInGrid() {
        TileRange r = TileRange.covering(-90, -180, 90, 180, 4);
        assertEquals(TileRange.world(4), r);
    }

    @Test
    void rejectsInvertedBoxes() {
        assertThrows(IllegalArgumentException.class, () -> TileRange.covering(39, 25, 38, 28, 5));
        assertThrows(IllegalArgumentException.class, () -> TileRange.covering(38, 28, 39, 25, 5));
        assertThrows(IllegalArgumentException.class, () -> TileRange.covering(38, 25, 39, 28, 6, 5));
    }
}
