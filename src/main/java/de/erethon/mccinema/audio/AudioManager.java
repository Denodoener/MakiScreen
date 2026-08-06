package de.erethon.mccinema.audio;

import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.diagnostics.ViewerDiagnosticsService;
import de.erethon.mccinema.platform.PlayerPlatform;
import de.erethon.mccinema.screen.Screen;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class AudioManager {

    public static final int DEFAULT_CHUNK_DURATION_MS = 10000; // 10 seconds per chunk by default
    public static final String SOUND_NAMESPACE = "mcc";

    private final MCCinema plugin;
    private final Screen screen;
    private final String videoId;
    private final File audioDir;
    private final List<AudioChunk> chunks = new ArrayList<>();
    private final int chunkDurationMs; // 0 = single file mode
    private final boolean positionalAudio; // broken right now
    private final UUID sessionId = UUID.randomUUID();
    private final String managerInstance = Integer.toHexString(System.identityHashCode(this));
    private final double radiusBlocks;
    private final float volume;
    private Set<UUID> targetPlayerIds;
    private final AudioRecipientTracker recipientTracker = new AudioRecipientTracker();

    private final AtomicInteger currentChunkIndex = new AtomicInteger(-1);
    private final AtomicBoolean isPlaying = new AtomicBoolean(false);
    private BukkitTask chunkScheduler;

    private long totalDurationMs;
    private long pausedAtMs;
    private long playbackStartTime;

    /**
     * Creates an AudioManager with configurable chunk duration.
     * @param chunkDurationMs Duration of each chunk in milliseconds. Use 0 for single file mode (no chunking).
     * @param positionalAudio If true, audio is extracted as mono for 3D positional playback. If false, stereo is kept for global playback.
     */
    public AudioManager(MCCinema plugin, String videoId, int chunkDurationMs, boolean positionalAudio, Screen screen) {
        this.plugin = plugin;
        this.videoId = videoId;
        this.chunkDurationMs = chunkDurationMs;
        this.positionalAudio = positionalAudio;
        this.screen = screen;
        this.radiusBlocks = Math.max(1.0, plugin.getConfig().getDouble("audio.radius-blocks", 20.0));
        this.volume = (float) Math.max(0.0, plugin.getConfig().getDouble("audio.volume", 1.0));
        // Include chunk duration and audio mode in folder name to separate cached files
        String chunkSuffix = chunkDurationMs == 0 ? "_single" : "_" + chunkDurationMs + "ms";
        String audioSuffix = positionalAudio ? "_mono" : "_stereo";
        this.audioDir = new File(plugin.getDataFolder(), "audio/" + videoId + chunkSuffix + audioSuffix);
    }

    public AudioManager(MCCinema plugin, AudioPackService.PreparedVideo prepared, Screen screen) {
        this.plugin = plugin;
        this.videoId = prepared.videoId();
        this.chunkDurationMs = prepared.catalogEntry().chunkDurationMs();
        this.positionalAudio = false;
        this.screen = screen;
        this.radiusBlocks = Math.max(1.0, plugin.getConfig().getDouble("audio.radius-blocks", 20.0));
        this.volume = (float) Math.max(0.0, plugin.getConfig().getDouble("audio.volume", 1.0));
        this.audioDir = prepared.chunks().isEmpty() ? plugin.getDataFolder()
            : prepared.chunks().getFirst().file().getParentFile();
        this.totalDurationMs = prepared.durationMs();
        this.chunks.addAll(prepared.chunks());
    }

    public int getChunkDurationMs() {
        return chunkDurationMs;
    }

    public boolean isSingleFileMode() {
        return chunkDurationMs == 0;
    }

    public String getVideoId() {
        return videoId;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public void setTargetPlayerIds(Collection<UUID> targetPlayerIds) {
        if (targetPlayerIds == null || targetPlayerIds.isEmpty()) {
            this.targetPlayerIds = null;
            return;
        }
        this.targetPlayerIds = new LinkedHashSet<>(targetPlayerIds);
    }

    public boolean extractAndSplitAudio(File videoFile) {
        return extractAndSplitAudio(videoFile, null);
    }

    public boolean extractAndSplitAudio(File videoFile, AudioProgressListener progressListener) {
        plugin.getLogger().info("Extracting audio from video" +
                                (isSingleFileMode() ? " (single file mode)" : " (chunk duration: " + chunkDurationMs + "ms)") + "...");
        try {
            AudioExtractor extractor = new AudioExtractor(
                plugin,
                videoFile,
                audioDir,
                chunkDurationMs,
                positionalAudio,
                progressListener == null ? null :
                    (stage, percent, detail) -> progressListener.onProgress(
                        stage.description(), percent, detail)
            );
            AudioExtractor.Result result = extractor.extract();
            totalDurationMs = result.durationMs();
            chunks.clear();
            chunks.addAll(result.chunks());
            plugin.getLogger().info("Audio extraction complete: " + chunks.size() + " chunk(s) created and cached");
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to extract audio: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    @FunctionalInterface
    public interface AudioProgressListener {
        void onProgress(String stage, int percent, String detail);
    }

    public void play(Location location) {
        if (chunks.isEmpty() || !isPlaying.compareAndSet(false, true)) {
            return;
        }

        currentChunkIndex.set(0);
        playbackStartTime = System.currentTimeMillis();
        logLifecycle("play", "chunk=0");
        playChunk(0, location);
        schedulePlaybackMonitor(location);
    }

    public void pause() {
        if (!isPlaying.get()) {
            return;
        }

        isPlaying.set(false);
        pausedAtMs = System.currentTimeMillis() - playbackStartTime;
        logLifecycle("pause", "at=" + pausedAtMs + "ms");

        if (chunkScheduler != null) {
            chunkScheduler.cancel();
        }

        stopAllSounds();
    }

    public void resume(Location location) {
        if (isPlaying.get() || chunks.isEmpty()) {
            return;
        }

        isPlaying.set(true);

        // For single file mode, just resume from beginning
        int chunkIndex = isSingleFileMode() ? 0 : (int) (pausedAtMs / chunkDurationMs);
        currentChunkIndex.set(chunkIndex);

        playbackStartTime = System.currentTimeMillis() - pausedAtMs;
        logLifecycle("resume", "at=" + pausedAtMs + "ms, chunk=" + chunkIndex);
        playChunk(chunkIndex, location);
        schedulePlaybackMonitor(location);
    }

    public void stop() {
        isPlaying.set(false);
        currentChunkIndex.set(-1);
        logLifecycle("stop", "at=" + Math.max(0L, System.currentTimeMillis() - playbackStartTime) + "ms");

        if (chunkScheduler != null) {
            chunkScheduler.cancel();
        }

        stopAllSounds();
    }

    public void seekTo(long timeMs, Location location) {
        boolean wasPlaying = isPlaying.get();
        seekTo(timeMs, location, wasPlaying);
    }

    public void seekTo(long timeMs, Location location, boolean resumePlayback) {
        stop();
        completeSeek(timeMs, location, resumePlayback);
    }

    public void completeSeek(long timeMs, Location location, boolean resumePlayback) {
        pausedAtMs = timeMs;
        logLifecycle("seek", "target=" + timeMs + "ms, resume=" + resumePlayback);

        if (resumePlayback) {
            resume(location);
        }
    }

    private void playChunk(int index, Location location) {
        if (index < 0 || index >= chunks.size()) {
            return;
        }

        AudioChunk chunk = chunks.get(index);
        if (index > 0 && hasProvenBoundaryOverlap(index - 1, index)) {
            stopSoundFor(recipientTracker.snapshot(), soundKey(chunks.get(index - 1)));
            plugin.getLogger().warning(audioLogPrefix() + "stopped overlapping previous chunk before chunk " + index);
        }
        Collection<Player> recipients = getAudioRecipients();
        String key = soundKey(chunk);
        Set<UUID> recipientIds = new LinkedHashSet<>();
        for (Player player : recipients) {
            player.playSound(location, key, SoundCategory.RECORDS, volume, 1.0f);
            recipientIds.add(player.getUniqueId());
        }
        Set<UUID> activeRecipients = recipientTracker.beginChunk(recipientIds);
        long actualMs = Math.max(0L, System.currentTimeMillis() - playbackStartTime);
        plugin.getLogger().info(audioLogPrefix() + "chunk=" + index + ", planned="
            + chunk.startMs() + "ms, actual=" + actualMs + "ms, duration="
            + chunk.durationMs() + "ms, recipients=" + activeRecipients);
    }

    private void stopAllSounds() {
        Set<UUID> recipients = recipientTracker.snapshot();
        for (Player player : getAudioRecipients()) {
            recipients.add(player.getUniqueId());
        }
        for (AudioChunk chunk : chunks) {
            stopSoundFor(recipients, soundKey(chunk));
        }
        recipientTracker.clear();
    }

    private Collection<Player> getAudioRecipients() {
        Collection<Player> candidates;
        if (targetPlayerIds == null || targetPlayerIds.isEmpty()) {
            candidates = new ArrayList<>(Bukkit.getOnlinePlayers());
        } else {
            List<Player> selectedPlayers = new ArrayList<>(targetPlayerIds.size());
            for (UUID playerId : targetPlayerIds) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null && player.isOnline()) {
                    selectedPlayers.add(player);
                }
            }
            candidates = selectedPlayers;
        }

        Location origin = screen.getOrigin();
        if (origin == null || origin.getWorld() == null) {
            return List.of();
        }
        ScreenAudioBounds bounds = ScreenAudioBounds.of(origin.getX(), origin.getY(), origin.getZ(),
            screen.getFacing() == null ? null : screen.getFacing().name(),
            screen.getMapWidth(), screen.getMapHeight());
        List<Player> recipients = new ArrayList<>(candidates.size());
        for (Player player : candidates) {
            Location playerLocation = player.getLocation();
            if (playerLocation.getWorld() == null || !playerLocation.getWorld().equals(origin.getWorld())
                || !bounds.containsWithinRadius(playerLocation.getX(), playerLocation.getY(),
                    playerLocation.getZ(), radiusBlocks)) {
                continue;
            }
            PlayerPlatform platform = plugin.getPlatformDetector().detect(player.getUniqueId());
            if (plugin.getAudioPackService().canPlayAudio(player)) {
                plugin.getViewerDiagnostics().setAudioMode(
                    player.getUniqueId(), platform == PlayerPlatform.JAVA
                        ? ViewerDiagnosticsService.AudioMode.JAVA_RESOURCE_PACK
                        : ViewerDiagnosticsService.AudioMode.BEDROCK_RESOURCE_PACK);
                recipients.add(player);
            } else {
                ViewerDiagnosticsService.AudioMode missingMode =
                    platform == PlayerPlatform.BEDROCK_VIA_GEYSER
                        ? ViewerDiagnosticsService.AudioMode.BEDROCK_PACK_REQUIRED
                        : ViewerDiagnosticsService.AudioMode.NONE;
                plugin.getViewerDiagnostics().setAudioMode(player.getUniqueId(), missingMode);
                plugin.getViewerDiagnostics().setResourcePackStatus(
                    player.getUniqueId(), platform == PlayerPlatform.BEDROCK_VIA_GEYSER
                        ? "BEDROCK_RECONNECT_OR_PACK_REQUIRED" : "SHARED_JAVA_PACK_NOT_LOADED");
            }
        }
        return recipients;
    }

    private void schedulePlaybackMonitor(Location location) {
        chunkScheduler = new BukkitRunnable() {
            @Override
            public void run() {
                if (!isPlaying.get()) {
                    cancel();
                    return;
                }

                long elapsedMs = System.currentTimeMillis() - playbackStartTime;
                int current = currentChunkIndex.get();
                pruneOutOfRadiusRecipients();

                int expectedChunk = isSingleFileMode() ? 0 : (int) (elapsedMs / chunkDurationMs);
                if (!isSingleFileMode() && expectedChunk > current && expectedChunk < chunks.size()) {
                    currentChunkIndex.set(expectedChunk);
                    playChunk(expectedChunk, location);
                }

                if ((!isSingleFileMode() && expectedChunk >= chunks.size())
                    || (isSingleFileMode() && elapsedMs >= totalDurationMs)) {
                    isPlaying.set(false);
                    recipientTracker.clear();
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void pruneOutOfRadiusRecipients() {
        Set<UUID> eligible = new HashSet<>();
        for (Player player : getAudioRecipients()) {
            eligible.add(player.getUniqueId());
        }
        Set<UUID> leaving = recipientTracker.prune(eligible);
        if (!leaving.isEmpty()) {
            int current = currentChunkIndex.get();
            if (current >= 0 && current < chunks.size()) {
                stopSoundFor(leaving, soundKey(chunks.get(current)));
            }
            plugin.getLogger().info(audioLogPrefix() + "stopped audio outside radius for " + leaving);
        }
    }

    private boolean hasProvenBoundaryOverlap(int previousIndex, int nextIndex) {
        AudioChunk previous = chunks.get(previousIndex);
        AudioChunk next = chunks.get(nextIndex);
        return previous.startMs() + previous.durationMs() > next.startMs() + 50L;
    }

    private void stopSoundFor(Collection<UUID> playerIds, String key) {
        for (UUID playerId : playerIds) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.stopSound(key, SoundCategory.RECORDS);
            }
        }
    }

    private String soundKey(AudioChunk chunk) {
        return SOUND_NAMESPACE + ":" + videoId + ".chunk_" + chunk.index();
    }

    private void logLifecycle(String action, String detail) {
        plugin.getLogger().info(audioLogPrefix() + action + " (" + detail + ")");
    }

    private String audioLogPrefix() {
        return "Audio session=" + sessionId + ", video=" + videoId + ", manager="
            + managerInstance + ", screen=" + screen.getId() + ": ";
    }

    public void cleanup() {
        stop();
    }

    public record AudioChunk(int index, long startMs, long durationMs, File file) {}
}

