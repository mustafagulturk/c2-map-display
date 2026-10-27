package io.github.mustafagulturk.c2map.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mustafagulturk.c2map.map.TileKey;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TopoJsonTest {

    @Test
    void miniJsonParsesAllTypes() {
        Object v = MiniJson.parse("{\"a\": [1, -2.5e1, true, false, null], \"s\": \"x\\\"\\u0130\\n\", \"o\": {}}");
        Map<?, ?> m = (Map<?, ?>) v;
        assertEquals(List.of(1.0, -25.0, true, false), ((List<?>) m.get("a")).subList(0, 4));
        assertNull(((List<?>) m.get("a")).get(4));
        assertEquals("x\"İ\n", m.get("s"));
        assertEquals(Map.of(), m.get("o"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("[1,"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("{} x"));
    }

    /** Two unit squares sharing the arc x=1; quantised with scale 1, translate (10, 20). */
    private static final String TOPO = """
            {"type":"Topology","transform":{"scale":[1,1],"translate":[10,20]},
             "objects":{"c":{"type":"GeometryCollection","geometries":[
                {"type":"Polygon","arcs":[[0,1]]},
                {"type":"Polygon","arcs":[[~1,2]]},
                {"type":"LineString","arcs":[2]}]}},
             "arcs":[[[1,0],[-1,0],[0,1],[1,0]],
                     [[1,1],[0,-1]],
                     [[1,1],[1,0],[0,-1],[-1,0]]]}
            """.replace("~1", "-2");

    @Test
    void decodesQuantisedArcsAndStitchesRings() {
        TopoJson t = TopoJson.parse(TOPO);
        List<List<double[]>> polys = t.polygons("c");
        assertEquals(2, polys.size());
        // Arc 0: (11,20) (10,20) (10,21) (11,21); arc 1: (11,21) (11,20) -> closed ring of 5 points.
        assertArrayEquals(new double[] {11, 20, 10, 20, 10, 21, 11, 21, 11, 20}, polys.get(0).get(0), 1e-9);
        // Arc 1 reversed (index ~1 = -2), then arc 2: (11,21) (12,21) (12,20) (11,20).
        assertArrayEquals(new double[] {11, 20, 11, 21, 12, 21, 12, 20, 11, 20}, polys.get(1).get(0), 1e-9);
        assertEquals(1, t.lines("c").size());
        List<double[]> shared = t.sharedArcs("c");
        assertEquals(2, shared.size()); // arc 1 (two polygons) and arc 2 (polygon + line)
        assertTrue(t.objectNames().contains("c"));
    }

    @Test
    void bundledDataRendersIzmirTile() throws Exception {
        System.setProperty("java.awt.headless", "true");
        DemoTileGenerator gen = new DemoTileGenerator();
        gen.loadData();
        BufferedImage img = gen.render(new TileKey(9, 294, 196));
        assertEquals(256, img.getWidth());
        // The tile contains both sea (Gulf of Izmir) and land.
        int sea = 0;
        int land = 0;
        for (int y = 0; y < 256; y += 4) {
            for (int x = 0; x < 256; x += 4) {
                int rgb = img.getRGB(x, y) & 0xffffff;
                if (rgb == 0x1d3443) {
                    sea++;
                } else if (rgb == 0x3a4540) {
                    land++;
                }
            }
        }
        assertTrue(sea > 200 && land > 200, "sea=" + sea + " land=" + land);
    }

    @Test
    void parsesCityLines() {
        DemoTileGenerator.City c = DemoTileGenerator.parseCity("İzmir\t38.41273\t27.13838\t2500603\tPPLA");
        assertEquals("İzmir", c.name());
        assertEquals(2_500_603, c.population());
        assertNull(DemoTileGenerator.parseCity("broken"));
        assertTrue(DemoTileGenerator.populationThreshold(11) <= DemoTileGenerator.populationThreshold(4));
    }
}
