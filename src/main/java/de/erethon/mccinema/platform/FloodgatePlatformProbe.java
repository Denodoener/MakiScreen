package de.erethon.mccinema.platform;

import org.geysermc.floodgate.api.FloodgateApi;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

final class FloodgatePlatformProbe implements PlatformProbe {

    private final Logger logger;
    private final AtomicBoolean failureLogged = new AtomicBoolean(false);

    FloodgatePlatformProbe(Logger logger) {
        this.logger = logger;
    }

    @Override
    public String name() {
        return "Floodgate API";
    }

    @Override
    public Optional<Boolean> isBedrockPlayer(UUID playerId) {
        try {
            FloodgateApi api = FloodgateApi.getInstance();
            if (api == null) {
                return Optional.empty();
            }
            return Optional.of(api.isFloodgatePlayer(playerId));
        } catch (RuntimeException | LinkageError error) {
            logFailureOnce(error);
            return Optional.empty();
        }
    }

    private void logFailureOnce(Throwable error) {
        if (failureLogged.compareAndSet(false, true)) {
            logger.warning("Floodgate API platform lookup failed; affected players are reported as UNKNOWN: "
                + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }
}
