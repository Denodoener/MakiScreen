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

    public static final int DEFAULT_CHUNK_DURATION_MS = 5000;
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
    private final Set<String> exclusionDiagnostics = new HashSet<>();

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
        this.targetPlayerIds.remove(null);
        if (this.targetPlayerIds.isEmpty()) {
            this.targetPlayerIds = null;
        }
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
        if (chunks.isEmpty() || isPlaying.get()) {
            return;
        }
        Collection<Player> initialRecipients = getAudioRecipients();
        AudioPlaybackStartPolicy.Decision startDecision =
            AudioPlaybackStartPolicy.evaluate(initialRecipients.size());
        if (!startDecision.canStart()) {
            plugin.getLogger().warning(audioLogPrefix()
                + "audio playback not started: " + startDecision.reason());
            return;
        }
        if (!isPlaying.compareAndSet(false, true)) {
            return;
        }

        currentChunkIndex.set(0);
        playbackStartTime = System.currentTimeMillis();
        logLifecycle("play", "chunk=0");
        playChunk(0, location, initialRecipients);
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

        Collection<Player> initialRecipients = getAudioRecipients();
        AudioPlaybackStartPolicy.Decision startDecision =
            AudioPlaybackStartPolicy.evaluate(initialRecipients.size());
        if (!startDecision.canStart()) {
            plugin.getLogger().warning(audioLogPrefix()
                + "audio resume rejected: " + startDecision.reason());
            return;
        }

        isPlaying.set(true);

        // For single file mode, just resume from beginning
        int chunkIndex = isSingleFileMode() ? 0 : (int) (pausedAtMs / chunkDurationMs);
        currentChunkIndex.set(chunkIndex);

        playbackStartTime = System.currentTimeMillis() - pausedAtMs;
        logLifecycle("resume", "at=" + pausedAtMs + "ms, chunk=" + chunkIndex);
        playChunk(chunkIndex, location, initialRecipients);
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
        playChunk(index, location, getAudioRecipients());
    }

    private void playChunk(int index, Location location, Collection<Player> recipients) {
        if (index < 0 || index >= chunks.size()) {
            return;
        }

        AudioChunk chunk = chunks.get(index);
        if (recipients.isEmpty()) {
            plugin.getLogger().warning(audioLogPrefix() + "chunk=" + index
                + " skipped: no eligible recipients; no sound command was sent");
            return;
        }
        if (index > 0 && hasProvenBoundaryOverlap(index - 1, index)) {
            stopSoundFor(recipientTracker.snapshot(), soundKey(chunks.get(index - 1)));
            plugin.getLogger().warning(audioLogPrefix() + "stopped overlapping previous chunk before chunk " + index);
        }
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

    public boolean hasEligibleRecipients() {
        return AudioPlaybackStartPolicy.evaluate(getAudioRecipients().size()).canStart();
    }

    private Collection<Player> getAudioRecipients() {
        Set<UUID> intendedPlayerIds = new LinkedHashSet<>();
        if (targetPlayerIds == null || targetPlayerIds.isEmpty()) {
            Bukkit.getOnlinePlayers().forEach(player -> intendedPlayerIds.add(player.getUniqueId()));
        } else {
            intendedPlayerIds.addAll(targetPlayerIds);
        }

        Location origin = screen.getOrigin();
        ScreenAudioBounds bounds = origin == null || origin.getWorld() == null ? null
            : ScreenAudioBounds.of(origin.getX(), origin.getY(), origin.getZ(),
                screen.getFacing() == null ? null : screen.getFacing().name(),
                screen.getMapWidth(), screen.getMapHeight());
        List<Player> recipients = new ArrayList<>(intendedPlayerIds.size());
        for (UUID playerId : intendedPlayerIds) {
            Player player = Bukkit.getPlayer(playerId);
            boolean online = player != null && player.isOnline();
            boolean withinRadius = false;
            if (online && bounds != null) {
                Location playerLocation = player.getLocation();
                withinRadius = playerLocation.getWorld() != null
                    && playerLocation.getWorld().equals(origin.getWorld())
                    && bounds.containsWithinRadius(playerLocation.getX(), playerLocation.getY(),
                        playerLocation.getZ(), radiusBlocks);
            }
            AudioPlaybackEligibility.Result eligibility = plugin.getAudioPackService()
                .evaluateAudioEligibility(playerId, online, withinRadius);
            if (eligibility.eligible()) {
                exclusionDiagnostics.removeIf(entry -> entry.startsWith(playerId + "|"));
                plugin.getViewerDiagnostics().setAudioMode(
                    playerId, eligibility.platform() == PlayerPlatform.JAVA
                        ? ViewerDiagnosticsService.AudioMode.JAVA_RESOURCE_PACK
                        : ViewerDiagnosticsService.AudioMode.BEDROCK_RESOURCE_PACK);
                recipients.add(player);
            } else {
                updateExcludedPlayerDiagnostics(playerId, eligibility);
                logExcludedPlayer(playerId, eligibility);
            }
        }
        return recipients;
    }

    private void updateExcludedPlayerDiagnostics(UUID playerId,
                                                  AudioPlaybackEligibility.Result eligibility) {
        ViewerDiagnosticsService.AudioMode missingMode =
            eligibility.platform() == PlayerPlatform.BEDROCK_VIA_GEYSER
                ? ViewerDiagnosticsService.AudioMode.BEDROCK_PACK_REQUIRED
                : ViewerDiagnosticsService.AudioMode.NONE;
        plugin.getViewerDiagnostics().setAudioMode(playerId, missingMode);
        plugin.getViewerDiagnostics().setResourcePackStatus(playerId, switch (eligibility.reason()) {
            case PLAYER_OFFLINE -> "PLAYER_OFFLINE";
            case OUTSIDE_RADIUS -> "OUTSIDE_AUDIO_RADIUS";
            case JAVA_PACK_NOT_HOSTED -> "SHARED_JAVA_PACK_NOT_HOSTED";
            case JAVA_PACK_NOT_LOADED -> "SHARED_JAVA_PACK_NOT_LOADED";
            case JAVA_PACK_STALE -> "SHARED_JAVA_PACK_STALE";
            case BEDROCK_PACK_NOT_READY -> "BEDROCK_PACK_NOT_READY";
            case BEDROCK_NOT_AUTHENTICATED -> "BEDROCK_NOT_AUTHENTICATED";
            case BEDROCK_PACK_NOT_LOADED -> "BEDROCK_PACK_NOT_LOADED";
            case BEDROCK_PACK_STALE -> "BEDROCK_RECONNECT_REQUIRED";
            case PLATFORM_UNKNOWN -> "PLATFORM_UNKNOWN";
            case ELIGIBLE -> "SHARED_PACK_LOADED";
        });
    }

    private void logExcludedPlayer(UUID playerId, AudioPlaybackEligibility.Result eligibility) {
        String diagnosticKey = playerId + "|" + eligibility.platform() + "|"
            + eligibility.catalogVersion() + "|" + eligibility.globalPackVersion() + "|"
            + eligibility.loadedPackVersion() + "|" + eligibility.reason() + "|"
            + eligibility.withinRadius();
        if (!exclusionDiagnostics.add(diagnosticKey)) {
            return;
        }
        plugin.getLogger().warning(audioLogPrefix() + "excluded player=" + playerId
            + ", platform=" + eligibility.platform()
            + ", global-pack-version=" + eligibility.catalogVersion()
            + ", global-pack-sha256=" + eligibility.globalPackVersion()
            + ", loaded-pack-version=" + eligibility.loadedPackVersion()
            + ", reason=" + eligibility.reason()
            + ", radius=" + (eligibility.withinRadius() ? "inside" : "outside"));
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
        exclusionDiagnostics.clear();
    }

    public record AudioChunk(int index, long startMs, long durationMs, File file) {}
}

