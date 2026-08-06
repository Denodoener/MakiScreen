package de.erethon.mccinema.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioPlaybackStartPolicyTest {

    @Test
    void audioDoesNotStartSilentlyWithEmptyRecipients() {
        AudioPlaybackStartPolicy.Decision decision = AudioPlaybackStartPolicy.evaluate(0);

        assertFalse(decision.canStart());
        assertTrue(decision.reason().contains("No intended player"));
    }

    @Test
    void atLeastOneCurrentRecipientAllowsPlaybackStart() {
        assertTrue(AudioPlaybackStartPolicy.evaluate(1).canStart());
    }
}
