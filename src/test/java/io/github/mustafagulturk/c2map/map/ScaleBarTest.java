package io.github.mustafagulturk.c2map.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ScaleBarTest {

    @Test
    void niceLengthsAreOneTwoFive() {
        assertEquals(1, ScaleBar.niceLength(1.9));
        assertEquals(2, ScaleBar.niceLength(2));
        assertEquals(2, ScaleBar.niceLength(4.99));
        assertEquals(5, ScaleBar.niceLength(9.99));
        assertEquals(10, ScaleBar.niceLength(10));
        assertEquals(1000, ScaleBar.niceLength(1000));
        assertEquals(1000, ScaleBar.niceLength(1999));
        assertEquals(20_000, ScaleBar.niceLength(43_210));
        assertEquals(0.5, ScaleBar.niceLength(0.7), 1e-12);
    }

    @Test
    void neverExceedsInput() {
        for (double m = 0.013; m < 1e7; m *= 1.37) {
            double nice = ScaleBar.niceLength(m);
            assertEquals(true, nice <= m && nice > m / 2.5 - 1e-9, m + " -> " + nice);
        }
    }

    @Test
    void labels() {
        assertEquals("500 m", ScaleBar.label(500));
        assertEquals("1 km", ScaleBar.label(1000));
        assertEquals("20 km", ScaleBar.label(20_000));
        assertEquals("0,5 m", ScaleBar.label(0.5));
    }

    @Test
    void rejectsNonPositive() {
        assertThrows(IllegalArgumentException.class, () -> ScaleBar.niceLength(0));
        assertThrows(IllegalArgumentException.class, () -> ScaleBar.niceLength(Double.NaN));
    }
}
