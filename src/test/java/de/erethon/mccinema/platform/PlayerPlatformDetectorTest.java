package de.erethon.mccinema.platform;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerPlatformDetectorTest {

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void fallsBackToJavaWithoutGeyserOrFloodgate() {
        PlayerPlatformDetector detector = PlayerPlatformDetector.withoutOptionalIntegrations();

        assertEquals(PlayerPlatform.JAVA, detector.detect(PLAYER_ID));
        assertEquals(List.of(), detector.activeIntegrations());
    }

    @Test
    void doesNotUseJavaFallbackWhileInstalledIntegrationsAreUnavailable() {
        PlayerPlatformDetector detector = PlayerPlatformDetector.awaitingOptionalIntegrations(
            List.of("Geyser API", "Floodgate API"));

        assertEquals(PlayerPlatform.UNKNOWN, detector.detect(PLAYER_ID));
        assertEquals(List.of("Geyser API", "Floodgate API"), detector.unavailableIntegrations());
    }

    @Test
    void optionalGeyserProbeDetectsBedrockPlayer() {
        PlayerPlatformDetector detector = new PlayerPlatformDetector(List.of(
            probe("Geyser API", Optional.of(true))
        ));

        assertEquals(PlayerPlatform.BEDROCK_VIA_GEYSER, detector.detect(PLAYER_ID));
        assertEquals(List.of("Geyser API"), detector.activeIntegrations());
    }

    @Test
    void floodgateCanDetectBedrockWhenGeyserApiIsTemporarilyUnavailable() {
        PlayerPlatformDetector detector = new PlayerPlatformDetector(List.of(
            probe("Geyser API", Optional.empty()),
            probe("Floodgate API", Optional.of(true))
        ));

        assertEquals(PlayerPlatform.BEDROCK_VIA_GEYSER, detector.detect(PLAYER_ID));
    }

    @Test
    void reportsUnknownWhenInstalledApisCannotAnswer() {
        PlayerPlatformDetector detector = new PlayerPlatformDetector(List.of(
            probe("Geyser API", Optional.empty()),
            probe("Floodgate API", Optional.empty())
        ));

        assertEquals(PlayerPlatform.UNKNOWN, detector.detect(PLAYER_ID));
    }

    @Test
    void isolatesOptionalApiFailureAndUsesRemainingProbe() {
        PlatformProbe failingProbe = new PlatformProbe() {
            @Override
            public String name() {
                return "Broken optional API";
            }

            @Override
            public Optional<Boolean> isBedrockPlayer(UUID playerId) {
                throw new IllegalStateException("not initialized");
            }
        };
        PlayerPlatformDetector detector = new PlayerPlatformDetector(List.of(
            failingProbe,
            probe("Floodgate API", Optional.of(false))
        ));

        assertEquals(PlayerPlatform.JAVA, detector.detect(PLAYER_ID));
    }

    @Test
    void doesNotRegisterDuplicateIntegrationProbes() {
        PlayerPlatformDetector detector = new PlayerPlatformDetector(List.of(
            probe("Geyser API", Optional.of(true)),
            probe("Geyser API", Optional.of(true)),
            probe("Floodgate API", Optional.of(false))
        ));

        assertEquals(List.of("Geyser API", "Floodgate API"), detector.activeIntegrations());
    }

    private static PlatformProbe probe(String name, Optional<Boolean> result) {
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
