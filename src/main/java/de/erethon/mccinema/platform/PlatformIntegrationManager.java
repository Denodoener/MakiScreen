package de.erethon.mccinema.platform;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class PlatformIntegrationManager {

    private final Supplier<PlayerPlatformDetector> detectorFactory;
    private final AtomicReference<PlayerPlatformDetector> current = new AtomicReference<>(
        PlayerPlatformDetector.awaitingOptionalIntegrations(List.of("server startup"))
    );

    public PlatformIntegrationManager(Supplier<PlayerPlatformDetector> detectorFactory) {
        this.detectorFactory = Objects.requireNonNull(detectorFactory, "detectorFactory");
    }

    public synchronized PlayerPlatformDetector refresh() {
        PlayerPlatformDetector replacement = Objects.requireNonNull(
            detectorFactory.get(), "detectorFactory returned null");
        current.set(replacement);
        return replacement;
    }

    public PlayerPlatformDetector current() {
        return current.get();
    }
}
