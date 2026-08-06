package de.erethon.mccinema.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenAudioBoundsTest {

    @Test
    void distanceIsMeasuredFromScreenVolumeNotItsCenter() {
        ScreenAudioBounds bounds = ScreenAudioBounds.of(0, 64, 0, "NORTH", 8, 5);

        assertTrue(bounds.containsWithinRadius(8, 66, 19.9, 20));
        assertFalse(bounds.containsWithinRadius(8, 66, 20.1, 20));
    }

    @Test
    void oppositeFacingsProduceCorrectNegativeExtent() {
        ScreenAudioBounds south = ScreenAudioBounds.of(10, 64, 4, "SOUTH", 8, 5);
        ScreenAudioBounds west = ScreenAudioBounds.of(10, 64, 4, "WEST", 8, 5);

        assertTrue(south.containsWithinRadius(2, 66, 4, 0));
        assertTrue(west.containsWithinRadius(10, 66, -4, 0));
    }
}
