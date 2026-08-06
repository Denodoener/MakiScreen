package de.erethon.mccinema.audio;

import de.erethon.mccinema.platform.PlayerPlatform;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioPlaybackEligibilityTest {

    @Test
    void staleJavaPlayerIsRejectedWithExactVersionContext() {
        AudioPlaybackEligibility.Result result = AudioPlaybackEligibility.evaluate(
            PlayerPlatform.JAVA, true, true, 4, true, false, "java-v4", "java-v3");

        assertFalse(result.eligible());
        assertEquals(AudioPlaybackEligibility.Reason.JAVA_PACK_STALE, result.reason());
        assertEquals(4, result.catalogVersion());
        assertEquals("java-v4", result.globalPackVersion());
        assertEquals("java-v3", result.loadedPackVersion());
        assertTrue(result.withinRadius());
    }

    @Test
    void staleBedrockPlayerIsRejectedWithReconnectCause() {
        AudioPlaybackEligibility.Result result = AudioPlaybackEligibility.evaluate(
            PlayerPlatform.BEDROCK_VIA_GEYSER, true, true, 4, true, true,
            "bedrock-v4", "bedrock-v3");

        assertFalse(result.eligible());
        assertEquals(AudioPlaybackEligibility.Reason.BEDROCK_PACK_STALE, result.reason());
        assertEquals("bedrock-v4", result.globalPackVersion());
        assertEquals("bedrock-v3", result.loadedPackVersion());
    }

    @Test
    void currentJavaPackAllowsAudio() {
        AudioPlaybackEligibility.Result result = AudioPlaybackEligibility.evaluate(
            PlayerPlatform.JAVA, true, true, 4, true, false, "java-v4", "java-v4");

        assertTrue(result.eligible());
    }

    @Test
    void currentAuthenticatedBedrockPackAllowsAudio() {
        AudioPlaybackEligibility.Result result = AudioPlaybackEligibility.evaluate(
            PlayerPlatform.BEDROCK_VIA_GEYSER, true, true, 4, true, true,
            "bedrock-v4", "bedrock-v4");

        assertTrue(result.eligible());
    }

    @Test
    void radiusAndAuthenticationAreIndependentEligibilityGates() {
        AudioPlaybackEligibility.Result outside = AudioPlaybackEligibility.evaluate(
            PlayerPlatform.JAVA, true, false, 4, true, false, "java-v4", "java-v4");
        AudioPlaybackEligibility.Result unauthenticatedBedrock = AudioPlaybackEligibility.evaluate(
            PlayerPlatform.BEDROCK_VIA_GEYSER, true, true, 4, true, false,
            "bedrock-v4", "bedrock-v4");

        assertEquals(AudioPlaybackEligibility.Reason.OUTSIDE_RADIUS, outside.reason());
        assertEquals(AudioPlaybackEligibility.Reason.BEDROCK_NOT_AUTHENTICATED,
            unauthenticatedBedrock.reason());
    }
}
