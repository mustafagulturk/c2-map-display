package io.github.mustafagulturk.c2map.zone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mustafagulturk.c2map.geo.GeoPoint;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZoneFileTest {

    @TempDir
    Path dir;

    @Test
    void roundTrip() throws IOException {
        RestrictedZone a = new RestrictedZone("id-1", "Bölge-1 Çığır\tşü",
                List.of(new GeoPoint(39.1234567, 32.7654321), new GeoPoint(39.2, 32.8), new GeoPoint(39.0, 32.9)),
                1_700_000_000_000L);
        RestrictedZone b = new RestrictedZone("id-2", "Bölge-2",
                List.of(new GeoPoint(-10, 179.9), new GeoPoint(-10, -179.9), new GeoPoint(-10.1, 180 - 360),
                        new GeoPoint(-10.2, 179.95)), 5);
        Path f = dir.resolve("sub/zones.txt");
        ZoneFile.save(f, List.of(a, b));
        List<RestrictedZone> loaded = ZoneFile.load(f);
        assertEquals(2, loaded.size());
        assertEquals("Bölge-1 Çığır şü", loaded.get(0).name());
        assertEquals(a.withName("Bölge-1 Çığır şü"), loaded.get(0));
        assertEquals(b, loaded.get(1));
        assertTrue(Files.readString(f, StandardCharsets.UTF_8).startsWith(ZoneFile.HEADER));
    }

    @Test
    void missingFileIsEmptyAndEmptyListRoundTrips() throws IOException {
        assertEquals(List.of(), ZoneFile.load(dir.resolve("none.txt")));
        Path f = dir.resolve("z.txt");
        ZoneFile.save(f, List.of());
        assertEquals(List.of(), ZoneFile.load(f));
    }

    @Test
    void malformedFilesAreRejected() throws IOException {
        Path f = dir.resolve("bad.txt");
        Files.writeString(f, "hello\n");
        assertThrows(IOException.class, () -> ZoneFile.load(f));
        Files.writeString(f, ZoneFile.HEADER + "\nzone\tid\t0\tn\nv\t1\t1\nv\t2\t2\n");
        IOException e = assertThrows(IOException.class, () -> ZoneFile.load(f));
        assertTrue(e.getMessage().contains("3 vertices"), e.getMessage());
        Files.writeString(f, ZoneFile.HEADER + "\nv\t1\t1\n");
        assertThrows(IOException.class, () -> ZoneFile.load(f));
        Files.writeString(f, ZoneFile.HEADER + "\nzone\tid\t0\tn\nv\tx\t1\n");
        e = assertThrows(IOException.class, () -> ZoneFile.load(f));
        assertTrue(e.getMessage().contains("line 3"), e.getMessage());
    }

    @Test
    void commentsAndBlankLinesIgnored() throws IOException {
        Path f = dir.resolve("c.txt");
        Files.writeString(f, ZoneFile.HEADER + "\n# yorum\n\nzone\tid\t0\tn\nv\t1\t1\n\nv\t2\t2\nv\t1\t2\n");
        assertEquals(3, ZoneFile.load(f).get(0).vertices().size());
    }
}
