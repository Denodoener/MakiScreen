package de.erethon.mccinema.platform;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BedrockFrameLimiter {

    private static final long ONE_SECOND_NANOS = 1_000_000_000L;

    public enum Outcome {
        ALLOW_INCREMENTAL,
        ALLOW_FULL_RESYNC,
        DROP_FPS_LIMIT,
        DROP_SCREEN_SIZE_LIMIT,
        DROP_BANDWIDTH_LIMIT;

        public boolean allowed() {
            return this == ALLOW_INCREMENTAL || this == ALLOW_FULL_RESYNC;
        }
    }

    public record Decision(Outcome outcome, long payloadBytes) {
        public boolean allowed() {
            return outcome.allowed();
        }
    }

    public record Settings(double maxFps, int maxMapWidth, int maxMapHeight, long maxBytesPerSecond) {
        public Settings {
            if (!Double.isFinite(maxFps) || maxFps <= 0.0) {
                throw new IllegalArgumentException("maxFps must be finite and greater than zero");
            }
            if (maxMapWidth <= 0 || maxMapHeight <= 0) {
                throw new IllegalArgumentException("maximum map dimensions must be greater than zero");
            }
            if (maxBytesPerSecond <= 0L) {
                throw new IllegalArgumentException("maxBytesPerSecond must be greater than zero");
            }
        }
    }

    private final Map<RouteKey, RouteState> routeStates = new ConcurrentHashMap<>();
    private final Map<UUID, BandwidthState> bandwidthStates = new ConcurrentHashMap<>();
    private volatile Settings settings;

    public BedrockFrameLimiter(Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public void updateSettings(Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        routeStates.values().forEach(state -> {
            synchronized (state) {
                state.lastAcceptedNanos = Long.MIN_VALUE;
                state.needsFullResync = true;
            }
        });
        bandwidthStates.clear();
    }

    public Settings settings() {
        return settings;
    }

    public Decision evaluate(UUID playerId, UUID screenId, PlayerPlatform platform, int mapWidth, int mapHeight,
                             long incrementalBytes, long fullFrameBytes, boolean fullFrameRequested,
                             long nowNanos) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(screenId, "screenId");
        Objects.requireNonNull(platform, "platform");
        if (platform != PlayerPlatform.BEDROCK_VIA_GEYSER) {
            return new Decision(fullFrameRequested ? Outcome.ALLOW_FULL_RESYNC : Outcome.ALLOW_INCREMENTAL,
                fullFrameRequested ? fullFrameBytes : incrementalBytes);
        }

        Settings current = settings;
        RouteState state = routeStates.computeIfAbsent(new RouteKey(playerId, screenId), ignored -> new RouteState());
        synchronized (state) {
            if (mapWidth > current.maxMapWidth() || mapHeight > current.maxMapHeight()) {
                state.needsFullResync = true;
                return new Decision(Outcome.DROP_SCREEN_SIZE_LIMIT, 0L);
            }

            boolean fullResync = fullFrameRequested || state.needsFullResync;
            long payloadBytes = Math.max(0L, fullResync ? fullFrameBytes : incrementalBytes);
            long minimumFrameInterval = Math.max(1L, (long) (ONE_SECOND_NANOS / current.maxFps()));
            if (state.lastAcceptedNanos != Long.MIN_VALUE
                && nowNanos - state.lastAcceptedNanos < minimumFrameInterval) {
                state.needsFullResync = true;
                return new Decision(Outcome.DROP_FPS_LIMIT, payloadBytes);
            }

            BandwidthState bandwidth = bandwidthStates.computeIfAbsent(playerId, ignored -> new BandwidthState());
            synchronized (bandwidth) {
                if (bandwidth.windowStartNanos == Long.MIN_VALUE
                    || nowNanos - bandwidth.windowStartNanos >= ONE_SECOND_NANOS
                    || nowNanos < bandwidth.windowStartNanos) {
                    bandwidth.windowStartNanos = nowNanos;
                    bandwidth.bytesInWindow = 0L;
                }
                if (payloadBytes > current.maxBytesPerSecond()
                    || bandwidth.bytesInWindow > current.maxBytesPerSecond() - payloadBytes) {
                    state.needsFullResync = true;
                    return new Decision(Outcome.DROP_BANDWIDTH_LIMIT, payloadBytes);
                }
                bandwidth.bytesInWindow += payloadBytes;
            }

            state.lastAcceptedNanos = nowNanos;
            state.needsFullResync = false;
            return new Decision(fullResync ? Outcome.ALLOW_FULL_RESYNC : Outcome.ALLOW_INCREMENTAL,
                payloadBytes);
        }
    }

    public void markTransportFailure(UUID playerId, UUID screenId) {
        RouteState state = routeStates.computeIfAbsent(new RouteKey(playerId, screenId), ignored -> new RouteState());
        synchronized (state) {
            state.needsFullResync = true;
        }
    }

    public void remove(UUID playerId) {
        routeStates.keySet().removeIf(key -> key.playerId().equals(playerId));
        bandwidthStates.remove(playerId);
    }

    private record RouteKey(UUID playerId, UUID screenId) {
    }

    private static final class RouteState {
        private long lastAcceptedNanos = Long.MIN_VALUE;
        private boolean needsFullResync;
    }

    private static final class BandwidthState {
        private long windowStartNanos = Long.MIN_VALUE;
        private long bytesInWindow;
    }
}
