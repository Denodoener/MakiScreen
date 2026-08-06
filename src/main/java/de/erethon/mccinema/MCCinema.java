package de.erethon.mccinema;

import de.erethon.mccinema.commands.MCommandCache;
import de.erethon.mccinema.diagnostics.ViewerDiagnosticsService;
import de.erethon.mccinema.dither.DitherLookupUtil;
import de.erethon.mccinema.download.YoutubeDownloadManager;
import de.erethon.mccinema.platform.BedrockFrameLimiter;
import de.erethon.mccinema.platform.PlatformDetectorFactory;
import de.erethon.mccinema.platform.PlatformIntegrationManager;
import de.erethon.mccinema.platform.PlayerPlatformDetector;
import de.erethon.mccinema.resourcepack.ResourcePackManager;
import de.erethon.mccinema.screen.Screen;
import de.erethon.mccinema.screen.ScreenManager;
import de.erethon.mccinema.video.PacketDispatcher;
import de.erethon.mccinema.video.VideoPlayer;
import de.erethon.bedrock.compatibility.Internals;
import de.erethon.bedrock.plugin.EPlugin;
import de.erethon.bedrock.plugin.EPluginSettings;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegLogCallback;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;


public final class MCCinema extends EPlugin implements Listener {

    private final Logger logger = getLogger();
    private static MCCinema instance;
    private MCommandCache commands;

    private ScreenManager screenManager;
    private ResourcePackManager resourcePackManager;
    private YoutubeDownloadManager youtubeDownloadManager;
    private ResourcePackListener resourcePackListener;
    private PlatformIntegrationManager platformIntegrations;
    private BedrockFrameLimiter bedrockFrameLimiter;
    private ViewerDiagnosticsService viewerDiagnostics;
    private final Map<UUID, VideoPlayer> videoPlayers = new ConcurrentHashMap<>();

    public MCCinema() {
        settings = EPluginSettings.builder()
                .internals(Internals.NEW)
                .economy(false)
                .build();
    }

    @Override
    public void onEnable() {
        super.onEnable();
        instance = this;

        configureFfmpegLogging();

        saveDefaultConfig();
        reloadConfig();
        boolean configDirty = false;
        for (String key : getConfig().getDefaults().getKeys(true)) {
            if (!getConfig().isSet(key) && !(getConfig().getDefaults().get(key) instanceof org.bukkit.configuration.ConfigurationSection)) {
                getConfig().set(key, getConfig().getDefaults().get(key));
                configDirty = true;
            }
        }
        if (configDirty) {
            saveConfig();
            logger.info("Config updated with new default values.");
        }

        platformIntegrations = new PlatformIntegrationManager(
            () -> PlatformDetectorFactory.create(getServer().getPluginManager(), logger));
        PlayerPlatformDetector initialDetector = platformIntegrations.refresh();
        bedrockFrameLimiter = new BedrockFrameLimiter(loadBedrockLimitSettings());
        viewerDiagnostics = new ViewerDiagnosticsService(platformIntegrations::current);
        logPlatformIntegrations(initialDetector, "onEnable");
        logBedrockLimits();

        new File(getDataFolder(), "videos").mkdirs();
        new File(getDataFolder(), "audio").mkdirs();
        new File(getDataFolder(), "resourcepack").mkdirs();

        logger.info("Initializing color lookup tables... This can take a few seconds, please wait.");
        DitherLookupUtil.init();

        screenManager = new ScreenManager(this);
        screenManager.loadScreens();

        youtubeDownloadManager = new YoutubeDownloadManager(this);

        if (getConfig().getBoolean("resourcepack.enabled", true)) {
            // Determine hosting mode
            String modeStr = getConfig().getString("resourcepack.mode", "MCPACKS").toUpperCase();
            ResourcePackManager.HostingMode mode;
            try {
                mode = ResourcePackManager.HostingMode.valueOf(modeStr);
            } catch (IllegalArgumentException e) {
                logger.warning("Invalid resourcepack.mode in config: " + modeStr + ", defaulting to MCPACKS");
                mode = ResourcePackManager.HostingMode.MCPACKS;
            }

            String address = getConfig().getString("resourcepack.local.address", "localhost");
            int port = getConfig().getInt("resourcepack.local.port", 8080);

            resourcePackManager = new ResourcePackManager(this, mode, address, port);

            logger.info("Resource pack configuration:");
            logger.info("  Mode: " + mode);
            if (mode == ResourcePackManager.HostingMode.LOCAL) {
                logger.info("  Local Address: " + address);
                logger.info("  Local Port: " + port);
            }
            logger.info("  Auto-apply: " + getConfig().getBoolean("resourcepack.auto-apply", true));
            logger.info("  Required: " + getConfig().getBoolean("resourcepack.required", false));
        } else {
            logger.info("Resource pack hosting is disabled in config");
        }

        commands = new MCommandCache(this);
        commands.register(this);
        setCommandCache(commands);
        getServer().getPluginManager().registerEvents(this, this);
        resourcePackListener = new ResourcePackListener(this);
        getServer().getPluginManager().registerEvents(resourcePackListener, this);
        getServer().getScheduler().runTask(this,
            () -> refreshPlatformIntegrations("server startup complete"));
        logger.info("MCCinema enabled!");
        logger.info("  Screens loaded: " + screenManager.getAllScreens().size());
    }

    private void configureFfmpegLogging() {
        try {
            FFmpegLogCallback.setLevel(avutil.AV_LOG_WARNING);
            avutil.av_log_set_level(avutil.AV_LOG_WARNING);
        } catch (Throwable t) {
            logger.warning("Could not configure FFmpeg log level: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    @Override
    public void onDisable() {
        for (VideoPlayer player : videoPlayers.values()) {
            player.shutdown();
        }
        videoPlayers.clear();
        if (resourcePackManager != null) {
            resourcePackManager.shutdown();
        }
        if (screenManager != null) {
            screenManager.saveScreens();
        }
        logger.info("MCCinema disabled!");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        viewerDiagnostics.snapshot(event.getPlayer().getUniqueId());
        // Send last frame to joining players
        resendScreenFramesAfterJoin(event.getPlayer(), 20L);
        resendScreenFramesAfterJoin(event.getPlayer(), 60L);
        resendScreenFramesAfterJoin(event.getPlayer(), 120L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        bedrockFrameLimiter.remove(playerId);
        viewerDiagnostics.remove(playerId);
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        String pluginName = event.getPlugin().getName();
        if (isPlayerPlatformPlugin(pluginName)) {
            refreshPlatformIntegrations("plugin enabled: " + pluginName);
        }
    }

    private void resendScreenFramesAfterJoin(Player player, long delayTicks) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    return;
                }

                PacketDispatcher dispatcher = new PacketDispatcher(MCCinema.this);
                for (Screen screen : screenManager.getAllScreens()) {
                    VideoPlayer videoPlayer = getVideoPlayer(screen);
                    if (videoPlayer != null && !videoPlayer.canSendTo(player)) {
                        continue;
                    }
                    dispatcher.sendLastFrameToPlayer(player, screen);
                }
            }
        }.runTaskLater(this, delayTicks);
    }

    public static MCCinema getInstance() {
        return instance;
    }

    public ScreenManager getScreenManager() {
        return screenManager;
    }

    public ResourcePackManager getResourcePackManager() {
        return resourcePackManager;
    }

    public YoutubeDownloadManager getYoutubeDownloadManager() {
        return youtubeDownloadManager;
    }

    public void registerVideoPlayer(Screen screen, VideoPlayer player) {
        videoPlayers.put(screen.getId(), player);
    }

    public void unregisterVideoPlayer(Screen screen) {
        VideoPlayer player = videoPlayers.remove(screen.getId());
        if (player != null) {
            player.shutdown();
        }
    }

    public VideoPlayer getVideoPlayer(Screen screen) {
        return videoPlayers.get(screen.getId());
    }

    public ResourcePackListener getResourcePackListener() {
        return resourcePackListener;
    }

    public PlayerPlatformDetector getPlatformDetector() {
        return platformIntegrations.current();
    }

    public BedrockFrameLimiter getBedrockFrameLimiter() {
        return bedrockFrameLimiter;
    }

    public ViewerDiagnosticsService getViewerDiagnostics() {
        return viewerDiagnostics;
    }

    public void reloadBedrockSettings() {
        bedrockFrameLimiter.updateSettings(loadBedrockLimitSettings());
        logBedrockLimits();
    }

    public void refreshPlatformIntegrations(String reason) {
        PlayerPlatformDetector replacement = platformIntegrations.refresh();
        logPlatformIntegrations(replacement, reason);
    }

    private void logPlatformIntegrations(PlayerPlatformDetector detector, String reason) {
        if (detector.activeIntegrations().isEmpty()) {
            logger.warning("Optional player platform integrations: NONE. "
                + "Bedrock players cannot be detected safely.");
        } else {
            logger.info("Optional player platform integrations: "
                + String.join(", ", detector.activeIntegrations()));
        }
        if (!detector.unavailableIntegrations().isEmpty()) {
            logger.warning("Installed but not yet available player platform integrations: "
                + String.join(", ", detector.unavailableIntegrations())
                + ". Players that cannot be classified remain UNKNOWN and use safe unbundled map packets.");
        }
        logger.fine("Player platform integrations refreshed: " + reason);
    }

    private static boolean isPlayerPlatformPlugin(String pluginName) {
        return "Geyser-Spigot".equalsIgnoreCase(pluginName)
            || "floodgate".equalsIgnoreCase(pluginName);
    }

    private BedrockFrameLimiter.Settings loadBedrockLimitSettings() {
        double maxFps = Math.max(1.0, getConfig().getDouble("bedrock.image.max-fps", 10.0));
        int maxMapWidth = Math.max(1, getConfig().getInt("bedrock.image.max-map-width", 8));
        int maxMapHeight = Math.max(1, getConfig().getInt("bedrock.image.max-map-height", 5));
        long maxBytesPerSecond = Math.max(16_384L,
            getConfig().getLong("bedrock.image.max-bytes-per-second", 4L * 1024L * 1024L));
        return new BedrockFrameLimiter.Settings(maxFps, maxMapWidth, maxMapHeight, maxBytesPerSecond);
    }

    private void logBedrockLimits() {
        BedrockFrameLimiter.Settings limits = bedrockFrameLimiter.settings();
        logger.info("Bedrock image safety limits (pending real-client validation): "
            + limits.maxFps() + " FPS, "
            + limits.maxMapWidth() + "x" + limits.maxMapHeight() + " maps, "
            + limits.maxBytesPerSecond() + " bytes/s");
    }
}
