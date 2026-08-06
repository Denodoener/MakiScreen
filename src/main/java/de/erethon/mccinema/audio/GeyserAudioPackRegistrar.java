package de.erethon.mccinema.audio;

import de.erethon.mccinema.MCCinema;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.geysermc.geyser.api.event.bedrock.SessionLoadResourcePacksEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineResourcePacksEvent;
import org.geysermc.geyser.api.pack.PackCodec;
import org.geysermc.geyser.api.pack.ResourcePack;

import java.nio.file.Files;
import java.nio.file.Path;

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
            audioPacks.setBedrockRegistered(audioPacks.bedrockPack() != null);
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
            subscribed = true;
            audioPacks.setBedrockRegistered(audioPacks.bedrockPack() != null);
            plugin.getLogger().info("Native Bedrock audio pack registered through the public Geyser resource-pack API");
        } catch (Throwable failure) {
            audioPacks.setBedrockRegistered(false);
            plugin.getLogger().warning("Geyser resource-pack API is not ready: "
                + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    private void onDefinePacks(GeyserDefineResourcePacksEvent event) {
        ResourcePack pack = currentPack();
        if (pack == null || event.resourcePacks().stream().anyMatch(existing -> existing.uuid().equals(pack.uuid()))) {
            return;
        }
        event.register(pack);
        audioPacks.setBedrockRegistered(true);
    }

    private void onSessionPacks(SessionLoadResourcePacksEvent event) {
        ResourcePack pack = currentPack();
        if (pack == null) {
            audioPacks.markBedrockConnectionWithoutPack(event.connection().javaUuid());
            return;
        }
        if (event.resourcePacks().stream().noneMatch(existing -> existing.uuid().equals(pack.uuid()))) {
            event.register(pack);
        }
        audioPacks.markBedrockPackForConnection(event.connection().javaUuid());
        audioPacks.setBedrockRegistered(true);
        plugin.getLogger().info("Attached MCCinema Bedrock audio pack to Geyser connection "
            + event.connection().javaUuid());
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
        audioPacks.setBedrockRegistered(false);
    }

    public boolean subscribed() {
        return subscribed;
    }
}
