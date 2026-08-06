package de.erethon.mccinema.audio;

/** Prevents an audio timeline from starting without an eligible recipient. */
final class AudioPlaybackStartPolicy {

    private AudioPlaybackStartPolicy() {
    }

    static Decision evaluate(int eligibleRecipients) {
        if (eligibleRecipients > 0) {
            return new Decision(true, "NONE");
        }
        return new Decision(false,
            "No intended player has the current shared audio-pack version within radius");
    }

    record Decision(boolean canStart, String reason) {
    }
}
