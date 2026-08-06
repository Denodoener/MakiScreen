package de.erethon.mccinema.platform;

import java.util.Optional;
import java.util.UUID;

public interface PlatformProbe {

    String name();

    /**
     * @return true for a Bedrock connection, false for a known Java connection,
     *         or empty when the optional API cannot currently answer.
     */
    Optional<Boolean> isBedrockPlayer(UUID playerId);
}
