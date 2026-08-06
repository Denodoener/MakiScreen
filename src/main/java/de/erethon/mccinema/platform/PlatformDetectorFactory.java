package de.erethon.mccinema.platform;

import org.bukkit.plugin.PluginManager;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Logger;

public final class PlatformDetectorFactory {

    private PlatformDetectorFactory() {
    }

    public static PlayerPlatformDetector create(PluginManager pluginManager, Logger logger) {
        IntegrationAvailability geyser = availability(pluginManager, "Geyser-Spigot");
        IntegrationAvailability floodgate = availability(pluginManager, "floodgate");
        return create(
            geyser,
            floodgate,
            () -> new GeyserPlatformProbe(logger),
            () -> new FloodgatePlatformProbe(logger),
            logger
        );
    }

    static PlayerPlatformDetector create(
        IntegrationAvailability geyser,
        IntegrationAvailability floodgate,
        Supplier<PlatformProbe> geyserProbeFactory,
        Supplier<PlatformProbe> floodgateProbeFactory,
        Logger logger
    ) {
        List<PlatformProbe> probes = new ArrayList<>(2);
        List<String> unavailable = new ArrayList<>(2);
        addProbe("Geyser API", geyser, geyserProbeFactory, probes, unavailable, logger);
        addProbe("Floodgate API", floodgate, floodgateProbeFactory, probes, unavailable, logger);
        return new PlayerPlatformDetector(probes, unavailable);
    }

    private static IntegrationAvailability availability(PluginManager pluginManager, String pluginName) {
        boolean installed = pluginManager.getPlugin(pluginName) != null;
        return new IntegrationAvailability(installed, installed && pluginManager.isPluginEnabled(pluginName));
    }

    private static void addProbe(
        String apiName,
        IntegrationAvailability availability,
        Supplier<PlatformProbe> probeFactory,
        List<PlatformProbe> probes,
        List<String> unavailable,
        Logger logger
    ) {
        if (!availability.installed()) {
            return;
        }
        if (!availability.enabled()) {
            unavailable.add(apiName);
            return;
        }
        try {
            probes.add(probeFactory.get());
        } catch (RuntimeException | LinkageError error) {
            unavailable.add(apiName);
            logger.warning(apiName + " plugin is enabled but its API is unavailable: "
                + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    record IntegrationAvailability(boolean installed, boolean enabled) {
        IntegrationAvailability {
            if (enabled && !installed) {
                throw new IllegalArgumentException("an enabled integration must also be installed");
            }
        }
    }
}
