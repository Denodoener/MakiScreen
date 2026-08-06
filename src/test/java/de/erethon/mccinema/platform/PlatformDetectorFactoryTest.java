package de.erethon.mccinema.platform;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlatformDetectorFactoryTest {

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final Logger LOGGER = Logger.getAnonymousLogger();

    @Test
    void installedButNotEnabledPluginsRemainPendingOnFirstInitialization() {
        AtomicInteger probeCreations = new AtomicInteger();
        PlayerPlatformDetector detector = PlatformDetectorFactory.create(
            new PlatformDetectorFactory.IntegrationAvailability(true, false),
            new PlatformDetectorFactory.IntegrationAvailability(true, false),
            () -> countingProbe("Geyser API", Optional.of(true), probeCreations),
            () -> countingProbe("Floodgate API", Optional.of(true), probeCreations),
            LOGGER
        );

        assertEquals(PlayerPlatform.UNKNOWN, detector.detect(PLAYER_ID));
        assertEquals(List.of(), detector.activeIntegrations());
        assertEquals(List.of("Geyser API", "Floodgate API"), detector.unavailableIntegrations());
        assertEquals(0, probeCreations.get());
    }

    @Test
    void laterEnabledGeyserAndFloodgateBecomeActiveExactlyOnce() {
        AtomicInteger probeCreations = new AtomicInteger();
        PlayerPlatformDetector detector = PlatformDetectorFactory.create(
            new PlatformDetectorFactory.IntegrationAvailability(true, true),
            new PlatformDetectorFactory.IntegrationAvailability(true, true),
            () -> countingProbe("Geyser API", Optional.of(true), probeCreations),
            () -> countingProbe("Floodgate API", Optional.of(true), probeCreations),
            LOGGER
        );

        assertEquals(PlayerPlatform.BEDROCK_VIA_GEYSER, detector.detect(PLAYER_ID));
        assertEquals(List.of("Geyser API", "Floodgate API"), detector.activeIntegrations());
        assertEquals(2, probeCreations.get());
    }

    private static PlatformProbe countingProbe(String name, Optional<Boolean> result, AtomicInteger creations) {
        creations.incrementAndGet();
        return new PlatformProbe() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Optional<Boolean> isBedrockPlayer(UUID playerId) {
                return result;
            }
        };
    }
}
