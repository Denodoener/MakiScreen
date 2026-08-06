package de.erethon.mccinema.platform;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class PlayerPlatformDetector {

    private final List<PlatformProbe> probes;
    private final List<String> unavailableIntegrations;

    public PlayerPlatformDetector(List<PlatformProbe> probes) {
        this(probes, List.of());
    }

    PlayerPlatformDetector(List<PlatformProbe> probes, List<String> unavailableIntegrations) {
        Objects.requireNonNull(probes, "probes");
        Objects.requireNonNull(unavailableIntegrations, "unavailableIntegrations");

        LinkedHashMap<String, PlatformProbe> uniqueProbes = new LinkedHashMap<>();
        for (PlatformProbe probe : probes) {
            PlatformProbe checkedProbe = Objects.requireNonNull(probe, "probe");
            uniqueProbes.putIfAbsent(checkedProbe.name(), checkedProbe);
        }
        this.probes = List.copyOf(uniqueProbes.values());
        this.unavailableIntegrations = unavailableIntegrations.stream().distinct().toList();
    }

    public static PlayerPlatformDetector withoutOptionalIntegrations() {
        return new PlayerPlatformDetector(List.of());
    }

    public static PlayerPlatformDetector awaitingOptionalIntegrations(List<String> integrationNames) {
        return new PlayerPlatformDetector(List.of(), integrationNames);
    }

    public PlayerPlatform detect(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (probes.isEmpty()) {
            return unavailableIntegrations.isEmpty() ? PlayerPlatform.JAVA : PlayerPlatform.UNKNOWN;
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

    public List<String> unavailableIntegrations() {
        return unavailableIntegrations;
    }
}
