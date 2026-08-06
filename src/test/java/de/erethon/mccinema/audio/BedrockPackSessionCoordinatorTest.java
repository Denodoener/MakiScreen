package de.erethon.mccinema.audio;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockPackSessionCoordinatorTest {

    private static final String PACK_VERSION = "bedrock-sha-256";

    @Test
    void nullJavaUuidStillRegistersPackAndDefersPlayerAssociation() {
        BedrockPackSessionCoordinator coordinator = new BedrockPackSessionCoordinator();
        Object connection = new Object();
        AtomicInteger registrations = new AtomicInteger();

        BedrockPackSessionCoordinator.SessionResult session = coordinator.attachSession(
            connection, null, PACK_VERSION, false, () -> {
                registrations.incrementAndGet();
                return true;
            });

        assertTrue(session.attached());
        assertTrue(session.newlyRegistered());
        assertTrue(session.uuidDeferred());
        assertEquals(1, registrations.get());
        assertEquals(1, coordinator.pendingSessions());

        UUID playerId = UUID.randomUUID();
        BedrockPackSessionCoordinator.JoinResult joined =
            coordinator.completeJoin(playerId, connection, PACK_VERSION);
        assertTrue(joined.authenticated());
        assertTrue(joined.usable());
        assertEquals(PACK_VERSION, joined.attachedVersion());
        assertEquals(0, coordinator.pendingSessions());
    }

    @Test
    void existingGlobalPackIsNotRegisteredTwice() {
        BedrockPackSessionCoordinator coordinator = new BedrockPackSessionCoordinator();
        AtomicInteger registrations = new AtomicInteger();

        BedrockPackSessionCoordinator.SessionResult session = coordinator.attachSession(
            new Object(), null, PACK_VERSION, true, () -> {
                registrations.incrementAndGet();
                return true;
            });

        assertTrue(session.attached());
        assertFalse(session.newlyRegistered());
        assertEquals(0, registrations.get());
    }

    @Test
    void subscriberRegistrationFailureDoesNotEscapeOrCreateState() {
        BedrockPackSessionCoordinator coordinator = new BedrockPackSessionCoordinator();

        BedrockPackSessionCoordinator.SessionResult session = coordinator.attachSession(
            new Object(), null, PACK_VERSION, false,
            () -> { throw new IllegalStateException("test failure"); });

        assertFalse(session.attached());
        assertTrue(session.failure().contains("test failure"));
        assertEquals(0, coordinator.pendingSessions());
    }

    @Test
    void twoSuccessiveBedrockConnectionsCanComplete() {
        BedrockPackSessionCoordinator coordinator = new BedrockPackSessionCoordinator();

        Object firstConnection = new Object();
        coordinator.attachSession(firstConnection, null, PACK_VERSION, false, () -> true);
        assertTrue(coordinator.completeJoin(UUID.randomUUID(), firstConnection, PACK_VERSION).usable());

        Object secondConnection = new Object();
        coordinator.attachSession(secondConnection, null, PACK_VERSION, false, () -> true);
        assertTrue(coordinator.completeJoin(UUID.randomUUID(), secondConnection, PACK_VERSION).usable());
        assertEquals(0, coordinator.pendingSessions());
    }

    @Test
    void disconnectBeforeJoinAndReloadLeaveNoOrphanedSessions() {
        BedrockPackSessionCoordinator coordinator = new BedrockPackSessionCoordinator();
        Object disconnectedBeforeJoin = new Object();
        coordinator.attachSession(disconnectedBeforeJoin, null, PACK_VERSION, false, () -> true);

        coordinator.disconnect(disconnectedBeforeJoin);
        assertEquals(0, coordinator.pendingSessions());
        assertNull(coordinator.completeJoin(UUID.randomUUID(), disconnectedBeforeJoin, PACK_VERSION)
            .attachedVersion());

        coordinator.attachSession(new Object(), null, PACK_VERSION, false, () -> true);
        coordinator.clear();
        assertEquals(0, coordinator.pendingSessions());
    }
}
