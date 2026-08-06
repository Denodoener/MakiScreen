package de.erethon.mccinema.audio;

import de.erethon.mccinema.MCCinema;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent;
import org.geysermc.geyser.api.event.bedrock.SessionLoadResourcePacksEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineResourcePacksEvent;
import org.geysermc.geyser.api.pack.PackCodec;
import org.geysermc.geyser.api.pack.ResourcePack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Public Geyser API integration for MCCinema's native Bedrock audio pack. */
public final class GeyserAudioPackRegistrar {

    private final MCCinema plugin;
    private final AudioPackService audioPacks;
    private EventRegistrar owner;
    private boolean subscribed;

    public GeyserAudioPackRegistrar(MCCinema plugin, AudioPackService audioPacks) {
        this.plugin = plugin;
        this.audioPacks = audioPacks;
    }

    public synchronized void refresh() {
        if (subscribed) {
            return;
        }
        try {
            GeyserApi api = GeyserApi.api();
            if (api == null) {
                return;
            }
            owner = EventRegistrar.of(this);
            api.eventBus().subscribe(owner, GeyserDefineResourcePacksEvent.class, this::onDefinePacks);
            api.eventBus().subscribe(owner, SessionLoadResourcePacksEvent.class, this::onSessionPacks);
            api.eventBus().subscribe(owner, SessionDisconnectEvent.class, this::onSessionDisconnect);
            subscribed = true;
            plugin.getLogger().info("Native Bedrock audio pack registered through the public Geyser resource-pack API");
        } catch (Throwable failure) {
            audioPacks.setBedrockGlobalRegistered(false);
            plugin.getLogger().warning("Geyser resource-pack API is not ready: "
                + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    private void onDefinePacks(GeyserDefineResourcePacksEvent event) {
        try {
            ResourcePack pack = currentPack();
            if (pack == null) {
                return;
            }
            boolean alreadyRegistered = event.resourcePacks().stream()
                .anyMatch(existing -> existing.uuid().equals(pack.uuid()));
            if (!alreadyRegistered) {
                event.register(pack);
            }
            audioPacks.setBedrockGlobalRegistered(true);
            plugin.getLogger().info(alreadyRegistered
                ? "MCCinema Bedrock pack was already present in Geyser's global pack definition"
                : "MCCinema Bedrock pack registered in Geyser's global pack definition");
        } catch (Throwable failure) {
            audioPacks.setBedrockGlobalRegistered(false);
            plugin.getLogger().warning("Could not define the global MCCinema Bedrock pack; "
                + "session registration remains available: " + failure.getClass().getSimpleName()
                + ": " + failure.getMessage());
        }
    }

    private void onSessionPacks(SessionLoadResourcePacksEvent event) {
        try {
            ResourcePack pack = currentPack();
            if (pack == null) {
                plugin.getLogger().info("No current MCCinema Bedrock pack was available during the early session event; "
                    + "no player UUID state was written");
                return;
            }

            UUID earlyJavaUuid = null;
            try {
                earlyJavaUuid = event.connection().javaUuid();
            } catch (Throwable ignored) {
                // The early pack phase does not require or consume this value.
            }
            boolean alreadyRegistered = event.resourcePacks().stream()
                .anyMatch(existing -> existing.uuid().equals(pack.uuid()));
            BedrockPackSessionCoordinator.SessionResult result =
                audioPacks.attachBedrockPackToSession(event.connection(), earlyJavaUuid,
                    alreadyRegistered, () -> event.register(pack));

            if (!result.attached()) {
                plugin.getLogger().warning("MCCinema Bedrock session pack was not attached; login continues without "
                    + "UUID state. Detail: " + result.failure());
                return;
            }
            plugin.getLogger().info(result.newlyRegistered()
                ? "MCCinema Bedrock session pack was registered for the early Geyser session"
                : "MCCinema Bedrock session pack was already present; duplicate registration was skipped");
            if (result.uuidDeferred()) {
                plugin.getLogger().info("Geyser Java UUID is not available during SessionLoadResourcePacksEvent; "
                    + "MCCinema defers player association until Bukkit PlayerJoinEvent");
            } else {
                plugin.getLogger().info("MCCinema defers the early session's UUID association until Bukkit "
                    + "PlayerJoinEvent even though Geyser already exposed a UUID");
            }
        } catch (Throwable failure) {
            plugin.getLogger().warning("Ignored MCCinema Bedrock session-pack subscriber failure so the Geyser "
                + "login can continue: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    private void onSessionDisconnect(SessionDisconnectEvent event) {
        try {
            audioPacks.bedrockSessionDisconnected(event.connection());
        } catch (Throwable failure) {
            plugin.getLogger().warning("Could not clear an early MCCinema Bedrock session: "
                + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    public boolean completePlayerJoin(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        if (audioPacks.isBedrockPlayerAuthenticated(playerId)) {
            return true;
        }
        try {
            GeyserApi api = GeyserApi.api();
            if (api == null) {
                return false;
            }
            var connection = api.connectionByUuid(playerId);
            if (connection == null) {
                return false;
            }
            BedrockPackSessionCoordinator.JoinResult result =
                audioPacks.finalizeBedrockPackForPlayer(playerId, connection);
            if (result.usable()) {
                plugin.getLogger().info("MCCinema Bedrock pack association completed after PlayerJoinEvent for "
                    + playerId);
            } else {
                plugin.getLogger().warning("Bedrock player " + playerId + " authenticated, but the current MCCinema "
                    + "pack was not attached to this session; audio remains unavailable until reconnect");
            }
            return result.authenticated();
        } catch (Throwable failure) {
            plugin.getLogger().warning("Could not finalize MCCinema Bedrock pack association after PlayerJoinEvent: "
                + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            return false;
        }
    }

    private ResourcePack currentPack() {
        Path path = audioPacks.bedrockPack();
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try {
            return ResourcePack.create(PackCodec.path(path));
        } catch (Throwable failure) {
            plugin.getLogger().warning("Cannot open native Bedrock audio pack: " + failure.getMessage());
            return null;
        }
    }

    public synchronized void shutdown() {
        if (!subscribed || owner == null) {
            return;
        }
        try {
            GeyserApi.api().eventBus().unregisterAll(owner);
        } catch (Throwable ignored) {
        }
        subscribed = false;
        owner = null;
        audioPacks.setBedrockGlobalRegistered(false);
        audioPacks.resetBedrockSessionAssociations();
    }

    public boolean subscribed() {
        return subscribed;
    }
}
