package de.erethon.mccinema.audio;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Bridges the early Geyser pack event to the later authenticated Bukkit join
 * without using a Java UUID before Geyser has assigned one.
 */
final class BedrockPackSessionCoordinator {

    private final Map<Object, String> attachedByConnection = new IdentityHashMap<>();

    synchronized SessionResult attachSession(Object connection, UUID earlyJavaUuid,
                                             String packVersion, boolean alreadyRegistered,
                                             BooleanSupplier registerPack) {
        boolean uuidDeferred = earlyJavaUuid == null;
        if (connection == null || packVersion == null || packVersion.isBlank()) {
            return new SessionResult(false, false, uuidDeferred, "missing connection or pack version");
        }

        boolean newlyRegistered = false;
        boolean attached = alreadyRegistered;
        if (!attached) {
            try {
                newlyRegistered = registerPack != null && registerPack.getAsBoolean();
                attached = newlyRegistered;
            } catch (RuntimeException | LinkageError failure) {
                return new SessionResult(false, false, uuidDeferred,
                    failure.getClass().getSimpleName() + ": " + failure.getMessage());
            }
        }
        if (attached) {
            attachedByConnection.put(connection, packVersion);
        }
        return new SessionResult(attached, newlyRegistered, uuidDeferred, "NONE");
    }

    synchronized JoinResult completeJoin(UUID playerId, Object connection, String currentPackVersion) {
        if (playerId == null || connection == null) {
            return new JoinResult(false, false, null);
        }
        String attachedVersion = attachedByConnection.remove(connection);
        boolean usable = currentPackVersion != null && currentPackVersion.equals(attachedVersion);
        return new JoinResult(true, usable, attachedVersion);
    }

    synchronized void disconnect(Object connection) {
        if (connection != null) {
            attachedByConnection.remove(connection);
        }
    }

    synchronized int pendingSessions() {
        return attachedByConnection.size();
    }

    synchronized void clear() {
        attachedByConnection.clear();
    }

    record SessionResult(boolean attached, boolean newlyRegistered,
                         boolean uuidDeferred, String failure) {
    }

    record JoinResult(boolean authenticated, boolean usable, String attachedVersion) {
    }
}
