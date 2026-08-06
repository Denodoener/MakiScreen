package de.erethon.mccinema.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioChunkDurationPolicyTest {

    @Test
    void configuredFiveSecondsIsAlsoTheDefaultPlayDuration() {
        AudioChunkDurationPolicy.Selection selection = AudioChunkDurationPolicy.select(5_000, null);

        assertEquals(5_000, AudioManager.DEFAULT_CHUNK_DURATION_MS);
        assertTrue(selection.accepted());
        assertEquals(5_000, selection.chunkDurationMs());
    }

    @Test
    void explicitConfiguredDurationIsAccepted() {
        AudioChunkDurationPolicy.Selection selection = AudioChunkDurationPolicy.select(5_000, "5");

        assertTrue(selection.accepted());
        assertEquals(5_000, selection.chunkDurationMs());
    }

    @Test
    void differentNumericDurationIsRejectedWithRebuildGuidance() {
        AudioChunkDurationPolicy.Selection selection = AudioChunkDurationPolicy.select(5_000, "10");

        assertFalse(selection.accepted());
        assertTrue(selection.message().contains("5-Sekunden-Chunks"));
        assertTrue(selection.message().contains("/mcc audiopack rebuild"));
    }

    @Test
    void singleCannotReplaceAChunkedSharedPack() {
        AudioChunkDurationPolicy.Selection selection = AudioChunkDurationPolicy.select(5_000, "single");

        assertFalse(selection.accepted());
        assertEquals(5_000, selection.chunkDurationMs());
    }

    @Test
    void singleIsAcceptedOnlyWhenItIsTheConfiguredPrebuiltVariant() {
        AudioChunkDurationPolicy.Selection selection = AudioChunkDurationPolicy.select(0, "single");

        assertTrue(selection.accepted());
        assertEquals(0, selection.chunkDurationMs());
    }
}
