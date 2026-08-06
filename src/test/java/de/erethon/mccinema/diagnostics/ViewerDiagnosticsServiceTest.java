package de.erethon.mccinema.diagnostics;

import de.erethon.mccinema.platform.PlatformProbe;
import de.erethon.mccinema.platform.PlayerPlatform;
import de.erethon.mccinema.platform.PlayerPlatformDetector;
import de.erethon.mccinema.platform.ViewerRoutingPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ViewerDiagnosticsServiceTest {

    @Test
    void reportsStructuredPerViewerCountersAndStatuses() {
        UUID playerId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        PlatformProbe geyser = new PlatformProbe() {
            @Override
            public String name() {
                return "Geyser API";
            }

            @Override
            public Optional<Boolean> isBedrockPlayer(UUID ignored) {
                return Optional.of(true);
            }
        };
        ViewerDiagnosticsService service = new ViewerDiagnosticsService(
            new PlayerPlatformDetector(List.of(geyser)));

        service.recordSent(playerId, "cinema", 4, 65_536L);
        service.recordDropped(playerId, "cinema", "DROP_FPS_LIMIT");
        service.recordFailed(playerId, "cinema", "TransportException");
        service.setAudioMode(playerId, ViewerDiagnosticsService.AudioMode.BEDROCK_PACK_REQUIRED);
        service.setResourcePackStatus(playerId, "BEDROCK_PACK_UNAVAILABLE");

        ViewerDiagnosticsService.Snapshot snapshot = service.snapshot(playerId);
        assertEquals(PlayerPlatform.BEDROCK_VIA_GEYSER, snapshot.platform());
        assertEquals(ViewerRoutingPolicy.ImagePath.GEYSER_TRANSLATED_JAVA_MAP_PACKETS,
            snapshot.imagePath());
        assertEquals("cinema", snapshot.activeScreen());
        assertEquals(1L, snapshot.sentFrames());
        assertEquals(1L, snapshot.droppedFrames());
        assertEquals(1L, snapshot.failedFrames());
        assertEquals(4L, snapshot.sentPackets());
        assertEquals(65_536L, snapshot.sentBytes());
        assertEquals("TransportException", snapshot.lastFrameIssue());
        assertEquals(ViewerDiagnosticsService.AudioMode.BEDROCK_PACK_REQUIRED, snapshot.audioMode());
        assertEquals("BEDROCK_PACK_UNAVAILABLE", snapshot.resourcePackStatus());
    }
}
