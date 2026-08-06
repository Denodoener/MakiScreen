package de.erethon.mccinema.commands;

import de.erethon.bedrock.command.ECommand;
import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.audio.AudioPackService;
import de.erethon.mccinema.diagnostics.ViewerDiagnosticsService;
import de.erethon.mccinema.platform.BedrockFrameLimiter;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

public final class BedrockDebugCommand extends ECommand {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final MCCinema plugin = MCCinema.getInstance();

    public BedrockDebugCommand() {
        setCommand("bedrockdebug");
        setPermission("mccinema.bedrockdebug");
        setPlayerCommand(true);
        setConsoleCommand(true);
        setMinArgs(0);
        setMaxArgs(1);
        setHelp("/mcc bedrockdebug <player>");
    }

    @Override
    public void onExecute(String[] args, CommandSender sender) {
        if (args.length < 2) {
            sender.sendMessage(MM.deserialize("<red>Usage: /mcc bedrockdebug <player>"));
            return;
        }

        Player player = Bukkit.getPlayerExact(args[1]);
        if (player == null || !player.isOnline()) {
            sender.sendMessage(MM.deserialize("<red>Player '" + args[1] + "' is not online."));
            return;
        }

        ViewerDiagnosticsService.Snapshot snapshot =
            plugin.getViewerDiagnostics().snapshot(player.getUniqueId());
        BedrockFrameLimiter.Settings limits = plugin.getBedrockFrameLimiter().settings();
        AudioPackService.Status audioPacks = plugin.getAudioPackService().status();
        String integrations;
        if (!plugin.getPlatformDetector().activeIntegrations().isEmpty()) {
            integrations = String.join(", ", plugin.getPlatformDetector().activeIntegrations());
        } else if (!plugin.getPlatformDetector().unavailableIntegrations().isEmpty()) {
            integrations = "NONE (pending: "
                + String.join(", ", plugin.getPlatformDetector().unavailableIntegrations()) + ")";
        } else {
            integrations = "NONE (Bedrock detection unavailable)";
        }

        String report = "<gold><bold>MCCinema viewer diagnostics</bold></gold>\n"
            + "<gray>Player: <white>" + player.getName() + "</white>\n"
            + "<gray>Platform: <white>" + snapshot.platform() + "</white>\n"
            + "<gray>Platform API: <white>" + integrations + "</white>\n"
            + "<gray>Image path: <white>" + snapshot.imagePath() + "</white>\n"
            + "<gray>Active screen: <white>" + snapshot.activeScreen() + "</white>\n"
            + "<gray>Frames sent / dropped / failed: <white>"
            + snapshot.sentFrames() + " / " + snapshot.droppedFrames() + " / " + snapshot.failedFrames()
            + "</white>\n"
            + "<gray>Packets / payload: <white>" + snapshot.sentPackets() + " / "
            + formatBytes(snapshot.sentBytes()) + "</white>\n"
            + "<gray>Last frame issue: <white>" + snapshot.lastFrameIssue() + "</white>\n"
            + "<gray>Audio mode: <white>" + snapshot.audioMode() + "</white>\n"
            + "<gray>Resource-pack status: <white>" + snapshot.resourcePackStatus() + "</white>\n"
            + "<gray>Shared Java audio pack: <white>" + audioPacks.state()
            + ", hosted=" + audioPacks.javaHosted() + "</white>\n"
            + "<gray>Native Bedrock audio pack: <white>" + audioPacks.state()
            + ", registered=" + audioPacks.bedrockRegistered()
            + ", reconnect-required=" + audioPacks.bedrockReconnectRequired() + "</white>\n"
            + "<gray>Bedrock limits: <white>" + limits.maxFps() + " FPS, "
            + limits.maxMapWidth() + "x" + limits.maxMapHeight() + " maps, "
            + formatBytes(limits.maxBytesPerSecond()) + "/s</white>\n"
            + "<green>Bedrock image path validated in the reported real-client test at 10 FPS.</green>";
        sender.sendMessage(MM.deserialize(report));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length != 2) {
            return List.of();
        }
        String prefix = args[1].toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream()
            .map(Player::getName)
            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
    }
}
