package de.erethon.mccinema.commands;

import de.erethon.bedrock.command.ECommand;
import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.audio.AudioPackService;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;

public final class AudioPackCommand extends ECommand {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final MCCinema plugin = MCCinema.getInstance();

    public AudioPackCommand() {
        setCommand("audiopack");
        setPermission("mccinema.audiopack");
        setPlayerCommand(true);
        setConsoleCommand(true);
        setMinArgs(0);
        setMaxArgs(1);
        setHelp("/mcc audiopack <status|rebuild>");
    }

    @Override
    public void onExecute(String[] args, CommandSender sender) {
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "status";
        if ("rebuild".equals(action)) {
            plugin.getAudioPackService().rebuildAsync(true, "admin command by " + sender.getName());
            sender.sendMessage(MM.deserialize("<yellow>Shared Java and Bedrock audio-pack rebuild started."));
            return;
        }
        if (!"status".equals(action)) {
            sender.sendMessage(MM.deserialize("<red>Usage: /mcc audiopack <status|rebuild>"));
            return;
        }

        AudioPackService.Status status = plugin.getAudioPackService().status();
        String report = "<gold><bold>MCCinema shared audio packs</bold></gold>\n"
            + "<gray>State: <white>" + status.state() + "</white>\n"
            + "<gray>Catalog: <white>version " + status.version() + ", " + status.videos()
            + " videos, " + status.sounds() + " sounds</white>\n"
            + "<gray>Java: <white>ready=" + status.javaHosted() + ", hash=" + status.javaSha256()
            + ", size=" + formatBytes(status.javaSize()) + "</white>\n"
            + "<gray>Bedrock: <white>ready=" + status.bedrockReady()
            + ", hash=" + status.bedrockSha256() + ", size=" + formatBytes(status.bedrockSize())
            + ", globally-registered=" + status.bedrockRegistered() + "</white>\n"
            + "<gray>Bedrock sessions: <white>pack-attached=" + status.bedrockPendingSessions()
            + ", authenticated=" + status.bedrockAuthenticatedPlayers()
            + ", usable=" + status.bedrockUsablePlayers() + "</white>\n"
            + "<gray>Bedrock reconnect required: <white>" + status.bedrockReconnectRequired() + "</white>\n"
            + "<gray>Failed videos: <white>" + (status.failedVideos().isEmpty()
                ? "NONE" : String.join("; ", status.failedVideos())) + "</white>\n"
            + "<gray>Last failure: <white>" + status.lastFailure() + "</white>";
        sender.sendMessage(MM.deserialize(report));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length != 2) {
            return List.of();
        }
        String prefix = args[1].toLowerCase(Locale.ROOT);
        return List.of("status", "rebuild").stream().filter(value -> value.startsWith(prefix)).toList();
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
