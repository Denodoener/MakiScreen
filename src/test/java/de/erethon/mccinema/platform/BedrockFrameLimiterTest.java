package de.erethon.mccinema.platform;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BedrockFrameLimiterTest {

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID SCREEN_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");

    @Test
    void neverLimitsJavaViewer() {
        BedrockFrameLimiter limiter = limiter(1.0, 1, 1, 1L);

        BedrockFrameLimiter.Decision decision = limiter.evaluate(
            PLAYER_ID, SCREEN_ID, PlayerPlatform.JAVA, 100, 100, 10_000L, 1_000_000L, false, 1L);

        assertEquals(BedrockFrameLimiter.Outcome.ALLOW_INCREMENTAL, decision.outcome());
    }

    @Test
    void dropsFastBedrockFrameThenRequestsFullLatestFrame() {
        BedrockFrameLimiter limiter = limiter(10.0, 8, 5, 1_000_000L);
        long firstFrame = 1_000_000_000L;

        assertEquals(BedrockFrameLimiter.Outcome.ALLOW_INCREMENTAL,
            evaluate(limiter, 100L, 800L, firstFrame).outcome());
        assertEquals(BedrockFrameLimiter.Outcome.DROP_FPS_LIMIT,
            evaluate(limiter, 100L, 800L, firstFrame + 50_000_000L).outcome());
        BedrockFrameLimiter.Decision recovered =
            evaluate(limiter, 100L, 800L, firstFrame + 150_000_000L);

        assertEquals(BedrockFrameLimiter.Outcome.ALLOW_FULL_RESYNC, recovered.outcome());
        assertEquals(800L, recovered.payloadBytes());
    }

    @Test
    void rejectsOversizedScreen() {
        BedrockFrameLimiter limiter = limiter(10.0, 8, 5, 1_000_000L);

        BedrockFrameLimiter.Decision decision = limiter.evaluate(
            PLAYER_ID, SCREEN_ID, PlayerPlatform.BEDROCK_VIA_GEYSER, 9, 5,
            100L, 800L, false, 1_000_000_000L);

        assertEquals(BedrockFrameLimiter.Outcome.DROP_SCREEN_SIZE_LIMIT, decision.outcome());
    }

    @Test
    void enforcesPerViewerByteBudgetAndRecoversNextWindow() {
        BedrockFrameLimiter limiter = limiter(100.0, 8, 5, 100L);
        long firstFrame = 1_000_000_000L;

        assertEquals(BedrockFrameLimiter.Outcome.ALLOW_INCREMENTAL,
            evaluate(limiter, 60L, 80L, firstFrame).outcome());
        assertEquals(BedrockFrameLimiter.Outcome.DROP_BANDWIDTH_LIMIT,
            evaluate(limiter, 60L, 80L, firstFrame + 20_000_000L).outcome());
        assertEquals(BedrockFrameLimiter.Outcome.ALLOW_FULL_RESYNC,
            evaluate(limiter, 60L, 80L, firstFrame + 1_100_000_000L).outcome());
    }

    @Test
    void configurationReloadForcesFullResyncForExistingRoute() {
        BedrockFrameLimiter limiter = limiter(10.0, 8, 5, 1_000_000L);
        long firstFrame = 1_000_000_000L;
        assertEquals(BedrockFrameLimiter.Outcome.ALLOW_INCREMENTAL,
            evaluate(limiter, 100L, 800L, firstFrame).outcome());

        limiter.updateSettings(new BedrockFrameLimiter.Settings(15.0, 8, 5, 1_000_000L));

        assertEquals(BedrockFrameLimiter.Outcome.ALLOW_FULL_RESYNC,
            evaluate(limiter, 100L, 800L, firstFrame + 1L).outcome());
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class,
            () -> new BedrockFrameLimiter.Settings(0.0, 8, 5, 100L));
        assertThrows(IllegalArgumentException.class,
            () -> new BedrockFrameLimiter.Settings(10.0, 0, 5, 100L));
        assertThrows(IllegalArgumentException.class,
            () -> new BedrockFrameLimiter.Settings(10.0, 8, 5, 0L));
    }

    private static BedrockFrameLimiter limiter(double fps, int width, int height, long bytesPerSecond) {
        return new BedrockFrameLimiter(new BedrockFrameLimiter.Settings(fps, width, height, bytesPerSecond));
    }

    private static BedrockFrameLimiter.Decision evaluate(BedrockFrameLimiter limiter,
                                                          long incrementalBytes,
                                                          long fullFrameBytes,
                                                          long nowNanos) {
        return limiter.evaluate(
            PLAYER_ID,
            SCREEN_ID,
            PlayerPlatform.BEDROCK_VIA_GEYSER,
            8,
            5,
            incrementalBytes,
            fullFrameBytes,
            false,
            nowNanos
        );
    }
}
