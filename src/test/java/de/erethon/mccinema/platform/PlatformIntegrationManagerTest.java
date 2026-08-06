package de.erethon.mccinema.platform;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlatformIntegrationManagerTest {

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000006");

    @Test
    void serverStartupRefreshReplacesPendingDetectorWithGeyserDetection() {
        AtomicReference<PlayerPlatformDetector> next = new AtomicReference<>(
            PlayerPlatformDetector.awaitingOptionalIntegrations(List.of("Geyser API")));
        PlatformIntegrationManager manager = new PlatformIntegrationManager(next::get);

        assertEquals(PlayerPlatform.UNKNOWN, manager.current().detect(PLAYER_ID));
        assertEquals(PlayerPlatform.UNKNOWN, manager.refresh().detect(PLAYER_ID));

        next.set(new PlayerPlatformDetector(List.of(probe("Geyser API", true))));
        assertEquals(PlayerPlatform.BEDROCK_VIA_GEYSER, manager.refresh().detect(PLAYER_ID));
    }

    @Test
    void reloadRefreshesDetectorAndKeepsJavaPlayersJava() {
        AtomicReference<PlayerPlatformDetector> next = new AtomicReference<>(
            new PlayerPlatformDetector(List.of(probe("Geyser API", true))));
        PlatformIntegrationManager manager = new PlatformIntegrationManager(next::get);
        manager.refresh();
        assertEquals(PlayerPlatform.BEDROCK_VIA_GEYSER, manager.current().detect(PLAYER_ID));

        next.set(new PlayerPlatformDetector(List.of(probe("Geyser API", false))));
        manager.refresh();
        assertEquals(PlayerPlatform.JAVA, manager.current().detect(PLAYER_ID));
    }

    @Test
    void repeatedRefreshDoesNotAccumulateIntegrations() {
        PlatformIntegrationManager manager = new PlatformIntegrationManager(() ->
            new PlayerPlatformDetector(List.of(
                probe("Geyser API", true),
                probe("Floodgate API", true)
            )));

        manager.refresh();
        manager.refresh();
        manager.refresh();

        assertEquals(List.of("Geyser API", "Floodgate API"),
            manager.current().activeIntegrations());
    }

    private static PlatformProbe probe(String name, boolean result) {
        return new PlatformProbe() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Optional<Boolean> isBedrockPlayer(UUID playerId) {
                return Optional.of(result);
            }
        };
    }
}
