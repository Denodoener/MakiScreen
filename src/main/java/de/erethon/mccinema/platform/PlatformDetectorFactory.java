package de.erethon.mccinema.platform;

import org.bukkit.plugin.PluginManager;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public final class PlatformDetectorFactory {

    private PlatformDetectorFactory() {
    }

    public static PlayerPlatformDetector create(PluginManager pluginManager, Logger logger) {
        List<PlatformProbe> probes = new ArrayList<>(2);
        if (pluginManager.isPluginEnabled("Geyser-Spigot")) {
            addGeyserProbe(probes, logger);
        }
        if (pluginManager.isPluginEnabled("floodgate")) {
            addFloodgateProbe(probes, logger);
        }
        return new PlayerPlatformDetector(probes);
    }

    private static void addGeyserProbe(List<PlatformProbe> probes, Logger logger) {
        try {
            probes.add(new GeyserPlatformProbe(logger));
        } catch (RuntimeException | LinkageError error) {
            logger.warning("Geyser-Spigot is enabled but its API is unavailable: "
                + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private static void addFloodgateProbe(List<PlatformProbe> probes, Logger logger) {
        try {
            probes.add(new FloodgatePlatformProbe(logger));
        } catch (RuntimeException | LinkageError error) {
            logger.warning("Floodgate is enabled but its API is unavailable: "
                + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }
}
