package de.erethon.mccinema.platform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewerRoutingPolicyTest {

    @Test
    void keepsJavaViewersOnExistingJavaPath() {
        assertEquals(ViewerRoutingPolicy.ImagePath.JAVA_MAP_PACKETS,
            ViewerRoutingPolicy.imagePath(PlayerPlatform.JAVA));
        assertTrue(ViewerRoutingPolicy.receivesJavaAudioPack(PlayerPlatform.JAVA));
    }

    @Test
    void keepsUnknownViewersOffUnsafeJavaFallbacks() {
        assertEquals(ViewerRoutingPolicy.ImagePath.SAFE_UNBUNDLED_MAP_PACKETS,
            ViewerRoutingPolicy.imagePath(PlayerPlatform.UNKNOWN));
        assertFalse(ViewerRoutingPolicy.receivesJavaAudioPack(PlayerPlatform.UNKNOWN));
    }

    @Test
    void routesDetectedBedrockViewerThroughGeyserMapTranslationOnly() {
        assertEquals(ViewerRoutingPolicy.ImagePath.GEYSER_TRANSLATED_JAVA_MAP_PACKETS,
            ViewerRoutingPolicy.imagePath(PlayerPlatform.BEDROCK_VIA_GEYSER));
        assertFalse(ViewerRoutingPolicy.receivesJavaAudioPack(PlayerPlatform.BEDROCK_VIA_GEYSER));
    }
}
