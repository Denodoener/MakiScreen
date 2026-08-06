package de.erethon.mccinema.audio;

import de.erethon.mccinema.platform.PlayerPlatform;

/** Keeps Java and native Bedrock pack delivery paths mutually exclusive. */
public final class AudioPackRouting {

    public enum PackKind { JAVA, BEDROCK, NONE }

    private AudioPackRouting() {
    }

    public static PackKind packFor(PlayerPlatform platform) {
        return switch (platform) {
            case JAVA -> PackKind.JAVA;
            case BEDROCK_VIA_GEYSER -> PackKind.BEDROCK;
            case UNKNOWN -> PackKind.NONE;
        };
    }
}
