package de.erethon.mccinema.audio;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackSessionRegistryTest {

    @Test
    void replacedSessionRejectsLateCallbacks() {
        PlaybackSessionRegistry registry = new PlaybackSessionRegistry();
        UUID screen = UUID.randomUUID();

        long first = registry.begin(screen);
        long replacement = registry.begin(screen);

        assertFalse(registry.isCurrent(screen, first));
        assertTrue(registry.isCurrent(screen, replacement));
    }

    @Test
    void screensHaveIndependentEpochs() {
        PlaybackSessionRegistry registry = new PlaybackSessionRegistry();
        UUID firstScreen = UUID.randomUUID();
        UUID secondScreen = UUID.randomUUID();

        long first = registry.begin(firstScreen);
        long second = registry.begin(secondScreen);
        registry.invalidate(firstScreen);

        assertFalse(registry.isCurrent(firstScreen, first));
        assertTrue(registry.isCurrent(secondScreen, second));
    }
}
