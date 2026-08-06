package de.erethon.mccinema.audio;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Null-safe UUID state for Java and fully authenticated Bedrock players. */
final class PlayerAudioPackState {

    private final Map<UUID, String> javaSent = new ConcurrentHashMap<>();
    private final Map<UUID, String> javaSentClientHash = new ConcurrentHashMap<>();
    private final Map<UUID, String> javaLoaded = new ConcurrentHashMap<>();
    private final Set<UUID> bedrockAuthenticated = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> bedrockLoaded = new ConcurrentHashMap<>();

    String javaSent(UUID playerId) {
        return playerId == null ? null : javaSent.get(playerId);
    }

    String javaSentClientHash(UUID playerId) {
        return playerId == null ? null : javaSentClientHash.get(playerId);
    }

    String javaLoaded(UUID playerId) {
        return playerId == null ? null : javaLoaded.get(playerId);
    }

    String bedrockLoaded(UUID playerId) {
        return playerId == null ? null : bedrockLoaded.get(playerId);
    }

    boolean markJavaSent(UUID playerId, String version, String clientHash) {
        if (!valid(playerId, version) || clientHash == null || clientHash.isBlank()) {
            return false;
        }
        javaSent.put(playerId, version);
        javaSentClientHash.put(playerId, clientHash);
        javaLoaded.remove(playerId);
        return true;
    }

    boolean markJavaLoaded(UUID playerId, String version) {
        if (!valid(playerId, version)) {
            return false;
        }
        javaLoaded.put(playerId, version);
        return true;
    }

    void clearJavaLoaded(UUID playerId) {
        if (playerId != null) {
            javaLoaded.remove(playerId);
        }
    }

    boolean authenticateBedrock(UUID playerId) {
        return playerId != null && bedrockAuthenticated.add(playerId);
    }

    boolean markBedrockLoaded(UUID playerId, String version) {
        if (!valid(playerId, version)) {
            return false;
        }
        bedrockAuthenticated.add(playerId);
        bedrockLoaded.put(playerId, version);
        return true;
    }

    void clearBedrockLoaded(UUID playerId) {
        if (playerId != null) {
            bedrockLoaded.remove(playerId);
        }
    }

    boolean isBedrockAuthenticated(UUID playerId) {
        return playerId != null && bedrockAuthenticated.contains(playerId);
    }

    boolean isBedrockUsable(UUID playerId, String currentVersion) {
        return valid(playerId, currentVersion)
            && currentVersion.equals(bedrockLoaded.get(playerId));
    }

    boolean hasStaleBedrockPlayer(String currentVersion) {
        if (currentVersion == null || currentVersion.isBlank()) {
            return !bedrockAuthenticated.isEmpty();
        }
        return bedrockAuthenticated.stream()
            .anyMatch(playerId -> !currentVersion.equals(bedrockLoaded.get(playerId)));
    }

    int authenticatedBedrockPlayers() {
        return bedrockAuthenticated.size();
    }

    int usableBedrockPlayers(String currentVersion) {
        if (currentVersion == null || currentVersion.isBlank()) {
            return 0;
        }
        return (int) bedrockAuthenticated.stream()
            .filter(playerId -> currentVersion.equals(bedrockLoaded.get(playerId)))
            .count();
    }

    void disconnect(UUID playerId) {
        if (playerId == null) {
            return;
        }
        javaSent.remove(playerId);
        javaSentClientHash.remove(playerId);
        javaLoaded.remove(playerId);
        bedrockAuthenticated.remove(playerId);
        bedrockLoaded.remove(playerId);
    }

    void clearBedrock() {
        bedrockAuthenticated.clear();
        bedrockLoaded.clear();
    }

    void clear() {
        javaSent.clear();
        javaSentClientHash.clear();
        javaLoaded.clear();
        clearBedrock();
    }

    private static boolean valid(UUID playerId, String value) {
        return playerId != null && value != null && !value.isBlank();
    }
}
