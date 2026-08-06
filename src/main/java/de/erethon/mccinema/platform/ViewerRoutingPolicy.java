package de.erethon.mccinema.platform;

public final class ViewerRoutingPolicy {

    public enum ImagePath {
        JAVA_MAP_PACKETS,
        GEYSER_TRANSLATED_JAVA_MAP_PACKETS
    }

    private ViewerRoutingPolicy() {
    }

    public static ImagePath imagePath(PlayerPlatform platform) {
        return platform == PlayerPlatform.BEDROCK_VIA_GEYSER
            ? ImagePath.GEYSER_TRANSLATED_JAVA_MAP_PACKETS
            : ImagePath.JAVA_MAP_PACKETS;
    }

    public static boolean receivesJavaAudioPack(PlayerPlatform platform) {
        return platform != PlayerPlatform.BEDROCK_VIA_GEYSER;
    }
}
