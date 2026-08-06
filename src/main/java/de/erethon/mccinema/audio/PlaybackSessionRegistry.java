package de.erethon.mccinema.audio;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns the monotonically increasing playback epoch for every screen.
 * Asynchronous loaders and resource-pack callbacks must present the epoch they
 * were created for before they may publish or start a player.
 */
public final class PlaybackSessionRegistry {

    private final AtomicLong nextEpoch = new AtomicLong();
    private final ConcurrentHashMap<UUID, Long> currentEpochs = new ConcurrentHashMap<>();

    public long begin(UUID screenId) {
        long epoch = nextEpoch.incrementAndGet();
        currentEpochs.put(screenId, epoch);
        return epoch;
    }

    public boolean isCurrent(UUID screenId, long epoch) {
        return currentEpochs.getOrDefault(screenId, -1L) == epoch;
    }

    public void invalidate(UUID screenId) {
        begin(screenId);
    }

    public long current(UUID screenId) {
        return currentEpochs.getOrDefault(screenId, -1L);
    }
}
