package de.erethon.mccinema.audio;

import de.erethon.mccinema.platform.PlayerPlatform;

/** Pure decision model for admitting a player to shared-pack audio playback. */
final class AudioPlaybackEligibility {

    enum Reason {
        ELIGIBLE,
        PLAYER_OFFLINE,
        OUTSIDE_RADIUS,
        PLATFORM_UNKNOWN,
        JAVA_PACK_NOT_HOSTED,
        JAVA_PACK_NOT_LOADED,
        JAVA_PACK_STALE,
        BEDROCK_PACK_NOT_READY,
        BEDROCK_NOT_AUTHENTICATED,
        BEDROCK_PACK_NOT_LOADED,
        BEDROCK_PACK_STALE
    }

    private AudioPlaybackEligibility() {
    }

    static Result evaluate(PlayerPlatform platform, boolean online, boolean withinRadius,
                           int catalogVersion, boolean packReady, boolean bedrockAuthenticated,
                           String globalPackVersion, String loadedPackVersion) {
        String globalVersion = printable(globalPackVersion);
        String loadedVersion = printable(loadedPackVersion);
        if (!online) {
            return rejected(platform, catalogVersion, globalVersion, loadedVersion,
                Reason.PLAYER_OFFLINE, withinRadius);
        }
        if (!withinRadius) {
            return rejected(platform, catalogVersion, globalVersion, loadedVersion,
                Reason.OUTSIDE_RADIUS, false);
        }
        if (platform == null || platform == PlayerPlatform.UNKNOWN) {
            return rejected(platform, catalogVersion, globalVersion, loadedVersion,
                Reason.PLATFORM_UNKNOWN, true);
        }
        if (platform == PlayerPlatform.JAVA) {
            if (!packReady) {
                return rejected(platform, catalogVersion, globalVersion, loadedVersion,
                    Reason.JAVA_PACK_NOT_HOSTED, true);
            }
            if ("NONE".equals(loadedVersion)) {
                return rejected(platform, catalogVersion, globalVersion, loadedVersion,
                    Reason.JAVA_PACK_NOT_LOADED, true);
            }
            if (!globalVersion.equals(loadedVersion)) {
                return rejected(platform, catalogVersion, globalVersion, loadedVersion,
                    Reason.JAVA_PACK_STALE, true);
            }
            return eligible(platform, catalogVersion, globalVersion, loadedVersion);
        }
        if (platform == PlayerPlatform.BEDROCK_VIA_GEYSER) {
            if (!packReady) {
                return rejected(platform, catalogVersion, globalVersion, loadedVersion,
                    Reason.BEDROCK_PACK_NOT_READY, true);
            }
            if (!bedrockAuthenticated) {
                return rejected(platform, catalogVersion, globalVersion, loadedVersion,
                    Reason.BEDROCK_NOT_AUTHENTICATED, true);
            }
            if ("NONE".equals(loadedVersion)) {
                return rejected(platform, catalogVersion, globalVersion, loadedVersion,
                    Reason.BEDROCK_PACK_NOT_LOADED, true);
            }
            if (!globalVersion.equals(loadedVersion)) {
                return rejected(platform, catalogVersion, globalVersion, loadedVersion,
                    Reason.BEDROCK_PACK_STALE, true);
            }
            return eligible(platform, catalogVersion, globalVersion, loadedVersion);
        }
        return rejected(platform, catalogVersion, globalVersion, loadedVersion,
            Reason.PLATFORM_UNKNOWN, true);
    }

    private static Result eligible(PlayerPlatform platform, int catalogVersion,
                                   String globalVersion, String loadedVersion) {
        return new Result(true, platform, catalogVersion, globalVersion, loadedVersion,
            Reason.ELIGIBLE, true);
    }

    private static Result rejected(PlayerPlatform platform, int catalogVersion,
                                   String globalVersion, String loadedVersion,
                                   Reason reason, boolean withinRadius) {
        return new Result(false, platform == null ? PlayerPlatform.UNKNOWN : platform,
            catalogVersion, globalVersion, loadedVersion, reason, withinRadius);
    }

    private static String printable(String version) {
        return version == null || version.isBlank() ? "NONE" : version;
    }

    record Result(boolean eligible, PlayerPlatform platform, int catalogVersion,
                  String globalPackVersion, String loadedPackVersion,
                  Reason reason, boolean withinRadius) {
    }
}
