package io.github.mustafagulturk.c2map.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class AppConfigTest {

    @Test
    void defaultsMatchRadarSimulator() {
        AppConfig c = AppConfig.parse(List.of());
        assertEquals(5005, c.plotPort());
        assertEquals(5006, c.trackPort());
        assertEquals(38.42, c.site().position().lat(), 1e-9);
        assertEquals(40_000, c.site().maxRangeM(), 1e-9);
        assertNull(c.replayFile());
        assertEquals(0, c.benchSeconds());
    }

    @Test
    void parsesOptions() {
        AppConfig c = AppConfig.parse(List.of("--site", "39.93, 32.85", "--range-km", "60", "--plot-port", "6000",
                "--replay", "a.c2rec", "--frames", "5"));
        assertEquals(39.93, c.site().position().lat(), 1e-9);
        assertEquals(32.85, c.site().position().lon(), 1e-9);
        assertEquals(60_000, c.site().maxRangeM(), 1e-9);
        assertEquals(6000, c.plotPort());
        assertEquals(Path.of("a.c2rec"), c.replayFile());
        assertEquals(5, c.screenshotFrames());
    }

    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> AppConfig.parse(List.of("--nope", "1")));
        assertThrows(IllegalArgumentException.class, () -> AppConfig.parse(List.of("--site")));
        assertThrows(IllegalArgumentException.class, () -> AppConfig.parse(List.of("--site", "38")));
        assertThrows(IllegalArgumentException.class, () -> AppConfig.parse(List.of("--site", "95,10")));
        assertThrows(IllegalArgumentException.class, () -> AppConfig.parse(List.of("--plot-port", "70000")));
        assertThrows(IllegalArgumentException.class, () -> AppConfig.parse(List.of("--help")));
    }
}
