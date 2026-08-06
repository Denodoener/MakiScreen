package de.erethon.mccinema.diagnostics;

import de.erethon.mccinema.platform.PlayerPlatform;
import de.erethon.mccinema.platform.PlayerPlatformDetector;
import de.erethon.mccinema.platform.ViewerRoutingPolicy;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public final class ViewerDiagnosticsService {

    public enum AudioMode {
        NONE,
        JAVA_RESOURCE_PACK,
        BEDROCK_PACK_REQUIRED
    }

    public record Snapshot(
        PlayerPlatform platform,
        ViewerRoutingPolicy.ImagePath imagePath,
        String activeScreen,
        long sentFrames,
        long droppedFrames,
        long failedFrames,
        long sentPackets,
        long sentBytes,
        String lastFrameIssue,
        AudioMode audioMode,
        String resourcePackStatus
    ) {
    }

    private final Supplier<PlayerPlatformDetector> platformDetectorSupplier;
    private final Map<UUID, MutableDiagnostics> diagnostics = new ConcurrentHashMap<>();

    public ViewerDiagnosticsService(PlayerPlatformDetector platformDetector) {
        this(fixedDetectorSupplier(platformDetector));
    }

    public ViewerDiagnosticsService(Supplier<PlayerPlatformDetector> platformDetectorSupplier) {
        this.platformDetectorSupplier = Objects.requireNonNull(
            platformDetectorSupplier, "platformDetectorSupplier");
    }

    public void recordSent(UUID playerId, String screenName, int packetCount, long bytes) {
        MutableDiagnostics state = state(playerId);
        if (screenName != null) {
            state.activeScreen = screenName;
        }
        state.sentFrames.incrementAndGet();
        state.sentPackets.addAndGet(Math.max(0, packetCount));
        state.sentBytes.addAndGet(Math.max(0L, bytes));
        state.lastFrameIssue = "NONE";
    }

    public void recordDropped(UUID playerId, String screenName, String reason) {
        MutableDiagnostics state = state(playerId);
        if (screenName != null) {
            state.activeScreen = screenName;
        }
        state.droppedFrames.incrementAndGet();
        state.lastFrameIssue = reason;
    }

    public void recordFailed(UUID playerId, String screenName, String reason) {
        MutableDiagnostics state = state(playerId);
        if (screenName != null) {
            state.activeScreen = screenName;
        }
        state.failedFrames.incrementAndGet();
        state.lastFrameIssue = reason;
    }

    public void setAudioMode(UUID playerId, AudioMode audioMode) {
        state(playerId).audioMode = Objects.requireNonNull(audioMode, "audioMode");
    }

    public void setResourcePackStatus(UUID playerId, String status) {
        state(playerId).resourcePackStatus = Objects.requireNonNull(status, "status");
    }

    public Snapshot snapshot(UUID playerId) {
        MutableDiagnostics state = state(playerId);
        PlayerPlatform platform = Objects.requireNonNull(
            platformDetectorSupplier.get(), "platformDetectorSupplier returned null").detect(playerId);
        return new Snapshot(
            platform,
            ViewerRoutingPolicy.imagePath(platform),
            state.activeScreen,
            state.sentFrames.get(),
            state.droppedFrames.get(),
            state.failedFrames.get(),
            state.sentPackets.get(),
            state.sentBytes.get(),
            state.lastFrameIssue,
            state.audioMode,
            state.resourcePackStatus
        );
    }

    public void clearActiveScreen(String screenName) {
        diagnostics.values().forEach(state -> {
            if (screenName.equals(state.activeScreen)) {
                state.activeScreen = "NONE";
                state.audioMode = AudioMode.NONE;
            }
        });
    }

    public void remove(UUID playerId) {
        diagnostics.remove(playerId);
    }

    private MutableDiagnostics state(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return diagnostics.computeIfAbsent(playerId, ignored -> new MutableDiagnostics());
    }

    private static Supplier<PlayerPlatformDetector> fixedDetectorSupplier(
        PlayerPlatformDetector platformDetector) {
        PlayerPlatformDetector checkedDetector = Objects.requireNonNull(platformDetector, "platformDetector");
        return () -> checkedDetector;
    }

    private static final class MutableDiagnostics {
        private final AtomicLong sentFrames = new AtomicLong();
        private final AtomicLong droppedFrames = new AtomicLong();
        private final AtomicLong failedFrames = new AtomicLong();
        private final AtomicLong sentPackets = new AtomicLong();
        private final AtomicLong sentBytes = new AtomicLong();
        private volatile String activeScreen = "NONE";
        private volatile String lastFrameIssue = "NONE";
        private volatile AudioMode audioMode = AudioMode.NONE;
        private volatile String resourcePackStatus = "NOT_SENT";
    }
}
