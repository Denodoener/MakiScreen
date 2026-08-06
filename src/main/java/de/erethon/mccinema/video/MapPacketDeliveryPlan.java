package de.erethon.mccinema.video;

import de.erethon.mccinema.platform.PlayerPlatform;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class MapPacketDeliveryPlan {

    public enum Transport {
        BUNDLE,
        INDIVIDUAL
    }

    public record Batch(int fromInclusive, int toExclusive, long delayTicks) {
        public Batch {
            if (fromInclusive < 0 || toExclusive < fromInclusive || delayTicks < 0L) {
                throw new IllegalArgumentException("invalid packet batch");
            }
        }
    }

    public record Plan(Transport transport, List<Batch> batches) {
        public Plan {
            Objects.requireNonNull(transport, "transport");
            batches = List.copyOf(Objects.requireNonNull(batches, "batches"));
        }

        public boolean usesBundle() {
            return transport == Transport.BUNDLE;
        }
    }

    private MapPacketDeliveryPlan() {
    }

    public static Plan create(PlayerPlatform platform, boolean bundlesEnabled, int packetCount,
                              boolean fullFrame, int safePacketsPerTick) {
        Objects.requireNonNull(platform, "platform");
        if (packetCount < 0) {
            throw new IllegalArgumentException("packetCount must not be negative");
        }
        if (safePacketsPerTick <= 0) {
            throw new IllegalArgumentException("safePacketsPerTick must be greater than zero");
        }
        if (packetCount == 0) {
            return new Plan(Transport.INDIVIDUAL, List.of());
        }

        if (platform == PlayerPlatform.JAVA && bundlesEnabled && packetCount > 1) {
            return new Plan(Transport.BUNDLE, List.of(new Batch(0, packetCount, 0L)));
        }
        if (platform == PlayerPlatform.JAVA) {
            return new Plan(Transport.INDIVIDUAL, List.of(new Batch(0, packetCount, 0L)));
        }

        List<Batch> batches = new ArrayList<>((packetCount + safePacketsPerTick - 1) / safePacketsPerTick);
        int batchIndex = 0;
        for (int from = 0; from < packetCount; from += safePacketsPerTick) {
            int to = Math.min(packetCount, from + safePacketsPerTick);
            batches.add(new Batch(from, to, fullFrame ? batchIndex : 0L));
            batchIndex++;
        }
        return new Plan(Transport.INDIVIDUAL, batches);
    }
}
