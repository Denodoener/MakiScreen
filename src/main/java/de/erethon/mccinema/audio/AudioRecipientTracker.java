package de.erethon.mccinema.audio;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Tracks recipients that actually heard the current chunk. Players entering
 * the radius are activated only at the next chunk boundary; players leaving
 * are removed immediately so their current sound can be stopped.
 */
final class AudioRecipientTracker {

    private final Set<UUID> active = new LinkedHashSet<>();

    synchronized Set<UUID> beginChunk(Collection<UUID> eligible) {
        active.clear();
        active.addAll(eligible);
        return snapshot();
    }

    synchronized Set<UUID> prune(Collection<UUID> eligible) {
        Set<UUID> leaving = snapshot();
        leaving.removeAll(eligible);
        active.removeAll(leaving);
        return leaving;
    }

    synchronized Set<UUID> snapshot() {
        return new LinkedHashSet<>(active);
    }

    synchronized void clear() {
        active.clear();
    }
}
