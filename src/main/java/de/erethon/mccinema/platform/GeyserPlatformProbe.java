package de.erethon.mccinema.platform;

import org.geysermc.geyser.api.GeyserApi;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

final class GeyserPlatformProbe implements PlatformProbe {

    private final Logger logger;
    private final AtomicBoolean failureLogged = new AtomicBoolean(false);

    GeyserPlatformProbe(Logger logger) {
        this.logger = logger;
    }

    @Override
    public String name() {
        return "Geyser API";
    }

    @Override
    public Optional<Boolean> isBedrockPlayer(UUID playerId) {
        try {
            GeyserApi api = GeyserApi.api();
            if (api == null) {
                return Optional.empty();
            }
            return Optional.of(api.isBedrockPlayer(playerId));
        } catch (RuntimeException | LinkageError error) {
            logFailureOnce(error);
            return Optional.empty();
        }
    }

    private void logFailureOnce(Throwable error) {
        if (failureLogged.compareAndSet(false, true)) {
            logger.warning("Geyser API platform lookup failed; affected players are reported as UNKNOWN: "
                + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }
}
