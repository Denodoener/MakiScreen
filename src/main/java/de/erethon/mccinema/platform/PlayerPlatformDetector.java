package de.erethon.mccinema.platform;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class PlayerPlatformDetector {

    private final List<PlatformProbe> probes;

    public PlayerPlatformDetector(List<PlatformProbe> probes) {
        this.probes = List.copyOf(Objects.requireNonNull(probes, "probes"));
    }

    public static PlayerPlatformDetector withoutOptionalIntegrations() {
        return new PlayerPlatformDetector(List.of());
    }

    public PlayerPlatform detect(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (probes.isEmpty()) {
            return PlayerPlatform.JAVA;
        }

        boolean definitiveJavaResult = false;
        for (PlatformProbe probe : probes) {
            java.util.Optional<Boolean> result;
            try {
                result = probe.isBedrockPlayer(playerId);
            } catch (RuntimeException | LinkageError ignored) {
                continue;
            }
            if (result.isEmpty()) {
                continue;
            }
            if (result.get()) {
                return PlayerPlatform.BEDROCK_VIA_GEYSER;
            }
            definitiveJavaResult = true;
        }

        return definitiveJavaResult ? PlayerPlatform.JAVA : PlayerPlatform.UNKNOWN;
    }

    public List<String> activeIntegrations() {
        return probes.stream().map(PlatformProbe::name).toList();
    }
}
