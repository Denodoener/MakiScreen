package de.erethon.mccinema.audio;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerAudioPackStateTest {

    @Test
    void everyUuidMapOperationRejectsNullKeysAndValuesWithoutThrowing() {
        PlayerAudioPackState state = new PlayerAudioPackState();

        assertFalse(state.markJavaSent(null, "version", "hash"));
        assertFalse(state.markJavaSent(UUID.randomUUID(), null, "hash"));
        assertFalse(state.markJavaLoaded(null, "version"));
        assertFalse(state.markBedrockLoaded(null, "version"));
        assertFalse(state.markBedrockLoaded(UUID.randomUUID(), null));
        state.clearJavaLoaded(null);
        state.clearBedrockLoaded(null);
        state.disconnect(null);

        assertEquals(0, state.authenticatedBedrockPlayers());
    }

    @Test
    void completedBedrockJoinsBecomeUsableAndJavaPlayersStayJava() {
        PlayerAudioPackState state = new PlayerAudioPackState();
        UUID firstBedrock = UUID.randomUUID();
        UUID secondBedrock = UUID.randomUUID();
        UUID javaPlayer = UUID.randomUUID();

        assertTrue(state.markBedrockLoaded(firstBedrock, "pack-v1"));
        assertTrue(state.markBedrockLoaded(secondBedrock, "pack-v1"));
        assertTrue(state.markJavaSent(javaPlayer, "java-v1", "client-hash"));

        assertTrue(state.isBedrockUsable(firstBedrock, "pack-v1"));
        assertTrue(state.isBedrockUsable(secondBedrock, "pack-v1"));
        assertFalse(state.isBedrockAuthenticated(javaPlayer));
        assertEquals(2, state.authenticatedBedrockPlayers());
        assertEquals(2, state.usableBedrockPlayers("pack-v1"));
    }

    @Test
    void disconnectAndReloadClearAuthenticatedAndUsableState() {
        PlayerAudioPackState state = new PlayerAudioPackState();
        UUID playerId = UUID.randomUUID();
        state.markBedrockLoaded(playerId, "pack-v1");

        state.disconnect(playerId);
        assertFalse(state.isBedrockAuthenticated(playerId));
        assertFalse(state.isBedrockUsable(playerId, "pack-v1"));

        state.markBedrockLoaded(UUID.randomUUID(), "pack-v1");
        state.clearBedrock();
        assertEquals(0, state.authenticatedBedrockPlayers());
        assertEquals(0, state.usableBedrockPlayers("pack-v1"));
    }
}
