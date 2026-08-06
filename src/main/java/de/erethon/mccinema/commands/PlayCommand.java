package de.erethon.mccinema.commands;

import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.audio.AudioChunkDurationPolicy;
import de.erethon.mccinema.audio.AudioManager;
import de.erethon.mccinema.audio.AudioPackService;
import de.erethon.mccinema.download.LivestreamResolver;
import de.erethon.mccinema.download.YoutubeDownloadManager;
import de.erethon.mccinema.screen.Screen;
import de.erethon.mccinema.video.FrameProcessor;
import de.erethon.mccinema.video.VideoPlayer;
import de.erethon.bedrock.command.ECommand;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Locale;

public class PlayCommand extends ECommand {

    private final MCCinema plugin = MCCinema.getInstance();
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private BukkitTask progressTask;
    private BossBar progressBar;

    public PlayCommand() {
        setCommand("play");
        setAliases("start");
        setPermission("mccinema.play");
        setPlayerCommand(true);
        setConsoleCommand(true);
        setMinArgs(0);
        setMaxArgs(99);
        setHelp("/mcc play <screen> <file-or-url> [--audio [configured_seconds]] [--dither <mode>] [players...]");
    }

    @Override
    public void onExecute(String[] args, CommandSender sender) {
        if (args.length < 3) {
            sender.sendMessage(MM.deserialize("<red>Usage: /mcc play <screen> <file-or-url> [--audio [configured_seconds]] [--dither <mode>] [players...]"));
            return;
        }

        String screenName = args[1];
        String fileName = args[2];

        // Parse optional flags
        boolean audioFlag = false;
        int chunkDurationMs = plugin.getAudioPackService().configuredChunkDurationMs();
        String requestedChunkArgument = null;
        FrameProcessor.DitheringMode ditheringMode = FrameProcessor.DitheringMode.FLOYD_STEINBERG_REDUCED; // Default
        List<String> targetPlayerNames = new ArrayList<>();

        // Parse arguments for --audio, --dither, and optional target players
        for (int i = 3; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("--audio")) {
                audioFlag = true;
                // Check for chunk duration argument
                if (i + 1 < args.length && isAudioChunkArgument(args[i + 1])) {
                    requestedChunkArgument = args[i + 1];
                    i++; // Skip the chunk duration argument
                }
            } else if (args[i].equalsIgnoreCase("--dither")) {
                if (i + 1 < args.length) {
                    String modeArg = args[i + 1].toUpperCase();
                    try {
                        ditheringMode = FrameProcessor.DitheringMode.valueOf(modeArg);
                    } catch (IllegalArgumentException e) {
                        sender.sendMessage(MM.deserialize("<red>Invalid dithering mode: " + args[i + 1]));
                        sender.sendMessage(MM.deserialize("<gray>Available modes: floyd_steinberg, floyd_steinberg_reduced, bayer_8x8, none"));
                        return;
                    }
                    i++; // Skip the mode argument
                } else {
                    sender.sendMessage(MM.deserialize("<red>Missing dithering mode after --dither"));
                    return;
                }
            } else if (args[i].startsWith("--")) {
                sender.sendMessage(MM.deserialize("<red>Unknown option: " + args[i]));
                return;
            } else {
                targetPlayerNames.add(args[i]);
            }
        }

        if (audioFlag) {
            AudioChunkDurationPolicy.Selection selection = AudioChunkDurationPolicy.select(
                chunkDurationMs, requestedChunkArgument);
            if (!selection.accepted()) {
                sender.sendMessage(MM.deserialize("<red>" + selection.message()));
                return;
            }
            chunkDurationMs = selection.chunkDurationMs();
        }

        final boolean withAudio = audioFlag;
        Set<UUID> targetPlayerIds = resolveTargetPlayers(targetPlayerNames, sender);
        if (targetPlayerIds == null) {
            return;
        }

        Optional<Screen> screenOpt = plugin.getScreenManager().getScreen(screenName);
        if (screenOpt.isEmpty()) {
            sender.sendMessage(MM.deserialize("<red>Screen '" + screenName + "' not found!"));
            return;
        }

        Screen screen = screenOpt.get();

        if (isUrl(fileName)) {
            playLivestream(sender, screen, fileName, withAudio, ditheringMode, targetPlayerIds);
            return;
        }

        File videoFile = new File(plugin.getDataFolder(), "videos/" + fileName);
        if (!videoFile.exists()) {
            videoFile = new File(plugin.getDataFolder(), fileName);
        }
        if (!videoFile.exists()) {
            sender.sendMessage(MM.deserialize("<red>Video file '" + fileName + "' not found!"));
            sender.sendMessage(MM.deserialize("<gray>Place videos in: <white>" +
                plugin.getDataFolder().getAbsolutePath() + "/videos/"));
            return;
        }

        // Invalidate every prior state, including LOADING/PAUSED and late callbacks.
        long playbackEpoch = plugin.beginPlaybackSession(screen);

        sender.sendMessage(MM.deserialize("<yellow>Loading video..."));

        File finalVideoFile = videoFile;
        int finalChunkDurationMs = chunkDurationMs;
        FrameProcessor.DitheringMode finalDitheringMode = ditheringMode;
        Set<UUID> finalTargetPlayerIds = targetPlayerIds;
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!plugin.isPlaybackSessionCurrent(screen, playbackEpoch)) {
                    return;
                }
                VideoPlayer player = new VideoPlayer(plugin, screen);
                player.setTargetPlayerIds(finalTargetPlayerIds);

                QualityCommand.applyScreenPreset(player, screen);

                // Set dithering mode before processing any frames
                player.getFrameProcessor().setDitheringMode(finalDitheringMode);

                AudioManager audioManager = null;

                if (withAudio) {
                    sender.sendMessage(MM.deserialize("<yellow>Prüfe vorbereitetes gemeinsames Audiopack ("
                        + AudioChunkDurationPolicy.describe(finalChunkDurationMs) + ")..."));

                    try {
                        AudioPackService.PlaybackPreparation preparation =
                            plugin.getAudioPackService().prepareVideo(finalVideoFile, finalChunkDurationMs);
                        if (preparation.ready()) {
                            audioManager = new AudioManager(plugin, preparation.preparedVideo(), screen);
                            audioManager.setTargetPlayerIds(finalTargetPlayerIds);
                            player.setAudioManager(audioManager);
                        } else {
                            sender.sendMessage(MM.deserialize("<yellow>" + preparation.message()));
                        }
                    } catch (Exception failure) {
                        plugin.getLogger().severe("Audio preparation failed: " + failure.getMessage());
                        sender.sendMessage(MM.deserialize(
                            "<yellow>Audio konnte nicht vorbereitet werden; das Video startet ohne Audio."));
                        audioManager = null;
                    }
                }

                AudioManager finalAudioManager = audioManager;
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (!plugin.isPlaybackSessionCurrent(screen, playbackEpoch)) {
                            player.shutdown();
                            return;
                        }
                        boolean audioEnabled = finalAudioManager != null;
                        if (audioEnabled && !finalAudioManager.hasEligibleRecipients()) {
                            player.setAudioManager(null);
                            audioEnabled = false;
                            sender.sendMessage(MM.deserialize(
                                "<red>Audio konnte nicht gestartet werden: Kein vorgesehener Spieler besitzt "
                                    + "die aktuelle Audiopack-Version innerhalb des Radius. Java-Spieler müssen "
                                    + "das neue Pack laden; Bedrock-Spieler müssen neu verbinden."));
                        }
                        if (!player.load(finalVideoFile)) {
                            sender.sendMessage(MM.deserialize("<red>Failed to load video!"));
                            player.shutdown();
                            return;
                        }

                        if (!plugin.registerVideoPlayer(screen, player, playbackEpoch)) {
                            return;
                        }

                        player.setOnComplete(p -> {
                            stopProgressBar();
                            Bukkit.broadcast(MM.deserialize("<gray>Video playback complete."));
                        });

                        player.setOnStateChange(p -> {
                            if (p.getState() == VideoPlayer.State.PLAYING) {
                                startProgressBar(p);
                            } else if (p.getState() == VideoPlayer.State.STOPPED || p.getState() == VideoPlayer.State.IDLE) {
                                stopProgressBar();
                            }
                        });

                        // Shared packs are distributed on connection, never per playback.
                        player.play();

                        String audioInfo = audioEnabled ? "\n<gray>  Audio: <green>✓ Enabled"
                            : withAudio ? "\n<gray>  Audio: <yellow>Not started" : "";
                        String ditherInfo = "\n<gray>  Dithering: <white>" + formatDitheringMode(finalDitheringMode);
                        String viewerInfo = formatViewerInfo(player);
                        sender.sendMessage(MM.deserialize(
                            "<green>▶ Now playing: <white>" + finalVideoFile.getName() +
                            "\n<gray>  On screen: <white>" + screen.getName() +
                            "\n<gray>  Duration: <white>" + VideoPlayer.formatDuration(player.getTotalDurationMs()) +
                            "\n<gray>  Frame rate: <white>" + String.format("%.1f", player.getFrameRate()) + " fps" +
                            ditherInfo +
                            viewerInfo +
                            audioInfo
                        ));
                    }
                }.runTask(plugin);
            }
        }.runTaskAsynchronously(plugin);
    }

    private void startProgressBar(VideoPlayer player) {
        if (progressBar != null) {
            stopProgressBar();
        }

        progressBar = BossBar.bossBar(
            Component.text("Loading..."),
            0f,
            BossBar.Color.GREEN,
            BossBar.Overlay.PROGRESS
        );

        for (Player p : getPlaybackViewers(player)) {
            p.showBossBar(progressBar);
        }

        progressTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (player.getState() != VideoPlayer.State.PLAYING) {
                    if (player.getState() == VideoPlayer.State.PAUSED) {
                        progressBar.color(BossBar.Color.YELLOW);
                    }
                    return;
                }

                float progress = player.isLiveStream() ? 1f : (float) player.getProgress();
                progressBar.progress(Math.min(1f, Math.max(0f, progress)));
                progressBar.color(BossBar.Color.GREEN);

                String currentTime = VideoPlayer.formatDuration(player.getCurrentTimeMs());
                String totalTime = player.isLiveStream() ? "LIVE" : VideoPlayer.formatDuration(player.getTotalDurationMs());

                if (player.isLiveStream()) {
                    progressBar.name(MM.deserialize("<red>LIVE</red> <white>" + currentTime + "</white>"));
                } else {
                    progressBar.name(MM.deserialize(
                        "<green>▶</green> <white>" + currentTime + " / " + totalTime + "</white>"
                    ));
                }
            }
        }.runTaskTimer(plugin, 0L, 10L);
    }

    private void stopProgressBar() {
        if (progressTask != null) {
            progressTask.cancel();
            progressTask = null;
        }

        if (progressBar != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.hideBossBar(progressBar);
            }
            progressBar = null;
        }
    }

    private String formatDitheringMode(FrameProcessor.DitheringMode mode) {
        return switch (mode) {
            case FLOYD_STEINBERG -> "Floyd-Steinberg (High Quality)";
            case FLOYD_STEINBERG_REDUCED -> "Floyd-Steinberg Reduced (Default)";
            case ATKINSON -> "Atkinson (Cleaner, Less Noise)";
            case STUCKI -> "Stucki (Smoother Gradients)";
            case BAYER_8X8 -> "Bayer 8x8 (Low Noise)";
            case NONE -> "None (Fastest)";
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return plugin.getScreenManager().getAllScreens().stream()
                .map(Screen::getName)
                .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                .toList();
        }

        if (args.length == 3) {
            File videosDir = new File(plugin.getDataFolder(), "videos");
            if (videosDir.exists()) {
                String[] files = videosDir.list((dir, name) ->
                    name.endsWith(".mp4") || name.endsWith(".mkv") ||
                    name.endsWith(".avi") || name.endsWith(".webm"));
                if (files != null) {
                    return Arrays.stream(files)
                        .filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase()))
                        .toList();
                }
            }
        }

        if (args.length >= 4) {
            String prevArg = args[args.length - 2];
            String currentArg = args[args.length - 1];

            if (prevArg.equalsIgnoreCase("--audio")) {
                return filterCompletions(currentArg, combinedCompletions(
                    List.of(AudioChunkDurationPolicy.argumentFor(
                        plugin.getAudioPackService().configuredChunkDurationMs())),
                    onlinePlayerNames()
                ));
            }

            if (prevArg.equalsIgnoreCase("--dither")) {
                return Arrays.stream(FrameProcessor.DitheringMode.values())
                    .map(Enum::name)
                    .filter(s -> s.toLowerCase().startsWith(args[args.length - 1].toLowerCase()))
                    .toList();
            }

            return filterCompletions(currentArg, combinedCompletions(
                List.of("--audio", "--dither"),
                onlinePlayerNames()
            ));
        }

        return List.of();
    }

    private void playLivestream(CommandSender sender, Screen screen, String sourceUrl, boolean requestedAudio,
                                FrameProcessor.DitheringMode ditheringMode, Set<UUID> targetPlayerIds) {
        YoutubeDownloadManager downloadManager = plugin.getYoutubeDownloadManager();

        if (!downloadManager.ensureInitialized()) {
            sender.sendMessage(MM.deserialize("<yellow>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
            sender.sendMessage(MM.deserialize("<gold><bold>Livestream Playback - First Time Setup"));
            sender.sendMessage(MM.deserialize(""));
            sender.sendMessage(MM.deserialize("<white>To play YouTube and Twitch livestreams, MCCinema needs <gold>yt-dlp</gold>."));
            sender.sendMessage(MM.deserialize("<gray>What will be downloaded:"));
            sender.sendMessage(MM.deserialize("<white>  • <gray>yt-dlp binary (<gold>~10MB</gold>)"));
            sender.sendMessage(MM.deserialize("<white>  • <gray>From: <gold>https://github.com/yt-dlp/yt-dlp"));
            sender.sendMessage(MM.deserialize(""));
            sender.sendMessage(MM.deserialize("<white>To proceed, run:"));
            sender.sendMessage(MM.deserialize("<click:suggest_command:/mcc download --accept " + sourceUrl + "><gold><bold>[CLICK HERE]</bold></gold></click> <gray>or type: <white>/mcc download --accept " + sourceUrl));
            sender.sendMessage(MM.deserialize("<gray>After setup finishes, run your play command again."));
            sender.sendMessage(MM.deserialize("<gray>This is a one-time setup. You won't be asked again."));
            sender.sendMessage(MM.deserialize("<yellow>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
            return;
        }

        if (!downloadManager.isReady()) {
            sender.sendMessage(MM.deserialize("<yellow>yt-dlp is still initializing... Please wait a moment and try again."));
            return;
        }

        if (requestedAudio) {
            sender.sendMessage(MM.deserialize("<yellow>⚠ Live audio is not supported yet; starting video-only livestream playback."));
        }

        long playbackEpoch = plugin.beginPlaybackSession(screen);
        sender.sendMessage(MM.deserialize("<yellow>Resolving livestream..."));

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!plugin.isPlaybackSessionCurrent(screen, playbackEpoch)) {
                    return;
                }
                try {
                    LivestreamResolver resolver = new LivestreamResolver(plugin, downloadManager);
                    LivestreamResolver.Livestream livestream = resolver.resolve(sourceUrl);

                    VideoPlayer player = new VideoPlayer(plugin, screen);
                    player.setTargetPlayerIds(targetPlayerIds);
                    QualityCommand.applyScreenPreset(player, screen);
                    player.getFrameProcessor().setDitheringMode(ditheringMode);

                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            if (!plugin.isPlaybackSessionCurrent(screen, playbackEpoch)) {
                                player.shutdown();
                                return;
                            }
                            if (!player.loadLiveStream(livestream.streamUrl(), livestream.displayName())) {
                                sender.sendMessage(MM.deserialize("<red>Failed to load livestream!"));
                                player.shutdown();
                                return;
                            }

                            if (!plugin.registerVideoPlayer(screen, player, playbackEpoch)) {
                                return;
                            }

                            player.setOnComplete(p -> {
                                stopProgressBar();
                                Bukkit.broadcast(MM.deserialize("<gray>Livestream playback ended."));
                            });

                            player.setOnStateChange(p -> {
                                if (p.getState() == VideoPlayer.State.PLAYING) {
                                    startProgressBar(p);
                                } else if (p.getState() == VideoPlayer.State.STOPPED || p.getState() == VideoPlayer.State.IDLE) {
                                    stopProgressBar();
                                }
                            });

                            player.play();
                            String ditherInfo = "\n<gray>  Dithering: <white>" + formatDitheringMode(ditheringMode);
                            String viewerInfo = formatViewerInfo(player);
                            sender.sendMessage(MM.deserialize(
                                "<green>▶ Now playing live: <white>" + livestream.displayName() +
                                "\n<gray>  On screen: <white>" + screen.getName() +
                                "\n<gray>  Duration: <red>LIVE" +
                                "\n<gray>  Frame rate: <white>" + String.format("%.1f", player.getFrameRate()) + " fps" +
                                ditherInfo +
                                viewerInfo +
                                "\n<gray>  Audio: <yellow>Not supported for livestreams"
                            ));
                        }
                    }.runTask(plugin);
                } catch (Exception e) {
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            sender.sendMessage(MM.deserialize("<red>Failed to resolve livestream!"));
                            sender.sendMessage(MM.deserialize("<gray>Error: " + e.getMessage()));
                        }
                    }.runTask(plugin);
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    private boolean isUrl(String source) {
        String lower = source.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private boolean isAudioChunkArgument(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.equals("single") || lower.equals("0")) {
            return true;
        }
        try {
            Integer.parseInt(lower);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private Set<UUID> resolveTargetPlayers(List<String> names, CommandSender sender) {
        if (names.isEmpty()) {
            return Set.of();
        }

        Set<UUID> playerIds = new LinkedHashSet<>();
        List<String> missing = new ArrayList<>();
        for (String name : names) {
            Player player = Bukkit.getPlayerExact(name);
            if (player == null || !player.isOnline()) {
                missing.add(name);
                continue;
            }
            playerIds.add(player.getUniqueId());
        }

        if (!missing.isEmpty()) {
            sender.sendMessage(MM.deserialize("<red>Unknown or offline player(s): " + String.join(", ", missing)));
            return null;
        }

        return playerIds;
    }

    private Collection<Player> getPlaybackViewers(VideoPlayer player) {
        return player.hasTargetPlayerLimit() ? player.getPlaybackViewers() : player.getScreen().getViewers();
    }

    private String formatViewerInfo(VideoPlayer player) {
        if (!player.hasTargetPlayerLimit()) {
            return "";
        }
        return "\n<gray>  Viewers: <white>" + player.getPlaybackViewers().size() + " selected";
    }

    private List<String> onlinePlayerNames() {
        return Bukkit.getOnlinePlayers().stream()
            .map(Player::getName)
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    private List<String> combinedCompletions(List<String> first, List<String> second) {
        List<String> completions = new ArrayList<>(first.size() + second.size());
        completions.addAll(first);
        completions.addAll(second);
        return completions;
    }

    private List<String> filterCompletions(String currentArg, List<String> completions) {
        String lower = currentArg.toLowerCase(Locale.ROOT);
        return completions.stream()
            .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower))
            .toList();
    }
}

