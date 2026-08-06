package de.erethon.mccinema.audio;

import de.erethon.mccinema.platform.PlayerPlatform;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AudioPackRoutingTest {

    @Test
    void javaAndBedrockNeverReceiveTheOtherClientPack() {
        assertEquals(AudioPackRouting.PackKind.JAVA,
            AudioPackRouting.packFor(PlayerPlatform.JAVA));
        assertEquals(AudioPackRouting.PackKind.BEDROCK,
            AudioPackRouting.packFor(PlayerPlatform.BEDROCK_VIA_GEYSER));
        assertEquals(AudioPackRouting.PackKind.NONE,
            AudioPackRouting.packFor(PlayerPlatform.UNKNOWN));
    }
}
