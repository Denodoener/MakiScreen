package de.erethon.mccinema.audio;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AudioRecipientTrackerTest {

    @Test
    void leavingPlayerStopsImmediatelyAndEntrantWaitsForNextChunk() {
        AudioRecipientTracker tracker = new AudioRecipientTracker();
        UUID leaving = UUID.randomUUID();
        UUID staying = UUID.randomUUID();
        UUID entering = UUID.randomUUID();
        tracker.beginChunk(Set.of(leaving, staying));

        assertEquals(Set.of(leaving), tracker.prune(Set.of(staying, entering)));
        assertEquals(Set.of(staying), tracker.snapshot());
        assertEquals(Set.of(staying, entering), tracker.beginChunk(Set.of(staying, entering)));
    }
}
