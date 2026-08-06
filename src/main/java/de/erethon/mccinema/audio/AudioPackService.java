package de.erethon.mccinema.audio;

import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.diagnostics.ViewerDiagnosticsService;
import de.erethon.mccinema.platform.PlayerPlatform;
import de.erethon.mccinema.resourcepack.ResourcePackManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Maintains one persistent audio catalog and atomically publishes the matching
 * Java and native Bedrock resource packs.
 */
public final class AudioPackService {

    public enum State { EMPTY, BUILDING, READY, FAILED }

    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "mkv", "avi", "webm", "mov", "m4v");
    private final MCCinema plugin;
    private final Object buildLock = new Object();
    private final Path root;
    private final File manifestFile;
    private final Map<String, AudioPackCatalog.Video> videos = new LinkedHashMap<>();
    private final PlayerAudioPackState playerPackState = new PlayerAudioPackState();
    private final BedrockPackSessionCoordinator bedrockSessions = new BedrockPackSessionCoordinator();
    private final Set<String> failedVideos = ConcurrentHashMap.newKeySet();

    private volatile State state = State.EMPTY;
    private volatile int catalogVersion;
    private volatile String javaSha256 = "NONE";
    private volatile String bedrockSha256 = "NONE";
    private volatile long javaSize;
    private volatile long bedrockSize;
    private volatile String lastFailure = "NONE";
    private volatile ResourcePackManager.HostedResourcePack hostedJavaPack;
    private volatile Path bedrockPack;
    private volatile boolean bedrockGlobalRegistered;
    private volatile boolean bedrockReconnectRequired;
    private volatile boolean shuttingDown;

    public AudioPackService(MCCinema plugin) {
        this.plugin = plugin;
        this.root = plugin.getDataFolder().toPath().resolve("audio-pack");
        this.manifestFile = root.resolve("manifest.yml").toFile();
        loadManifest();
    }

    public void startInitialBuild() {
        rebuildAsync(false, "startup catalog scan");
    }

    public void rebuildAsync(boolean force, String reason) {
        if (state == State.BUILDING) {
            plugin.getLogger().info("Audio pack build already in progress; ignored duplicate request: " + reason);
            return;
        }
        state = State.BUILDING;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                rebuildAll(force, reason);
            } catch (Exception e) {
                fail("Audio pack rebuild failed: " + e.getMessage(), e);
            }
        });
    }

    public PreparedVideo prepareVideo(File videoFile, int chunkDurationMs,
                                      AudioManager.AudioProgressListener listener) throws Exception {
        synchronized (buildLock) {
            ensureRunning();
            PreparedVideo prepared;
            try {
                prepared = prepareVideoInternal(videoFile, chunkDurationMs, listener, false);
            } catch (Exception failure) {
                recordVideoFailure(videoFile, failure);
                throw failure;
            }
            AudioPackCatalog.Video existing = videos.get(prepared.videoId());
            if (!sameEntry(existing, prepared.catalogEntry())) {
                int previousVersion = catalogVersion;
                videos.put(prepared.videoId(), prepared.catalogEntry());
                catalogVersion = Math.max(1, catalogVersion + 1);
                try {
                    publishCatalog("video prepared: " + videoFile.getName());
                    clearVideoFailure(videoFile);
                } catch (Exception failure) {
                    if (existing == null) {
                        videos.remove(prepared.videoId());
                    } else {
                        videos.put(prepared.videoId(), existing);
                    }
                    catalogVersion = previousVersion;
                    throw failure;
                }
            } else if (state != State.READY || hostedJavaPack == null || bedrockPack == null) {
                publishCatalog("restore missing shared pack");
            }
            return prepared;
        }
    }

    private void rebuildAll(boolean force, String reason) throws Exception {
        synchronized (buildLock) {
            ensureRunning();
            state = State.BUILDING;
            File videosDirectory = new File(plugin.getDataFolder(), "videos");
            List<File> sources = listVideos(videosDirectory);
            Map<String, AudioPackCatalog.Video> rebuilt = new LinkedHashMap<>();
            Map<String, AudioPackCatalog.Video> previous = new LinkedHashMap<>(videos);
            int previousVersion = catalogVersion;
            int defaultChunkDuration = Math.max(0,
                plugin.getConfig().getInt("audio.chunk-duration-ms", AudioManager.DEFAULT_CHUNK_DURATION_MS));
            for (File source : sources) {
                try {
                    PreparedVideo prepared = prepareVideoInternal(source, defaultChunkDuration, null, force);
                    rebuilt.put(prepared.videoId(), prepared.catalogEntry());
                } catch (Exception failure) {
                    recordVideoFailure(source, failure);
                    throw failure;
                }
            }
            boolean changed = force || !catalogEquivalent(videos.values(), rebuilt.values());
            videos.clear();
            videos.putAll(rebuilt);
            if (changed || catalogVersion == 0) {
                catalogVersion = Math.max(1, catalogVersion + 1);
            }
            try {
                publishCatalog(reason);
                failedVideos.clear();
                cleanupOrphanCaches();
            } catch (Exception failure) {
                videos.clear();
                videos.putAll(previous);
                catalogVersion = previousVersion;
                throw failure;
            }
        }
    }

    private PreparedVideo prepareVideoInternal(File videoFile, int chunkDurationMs,
                                               AudioManager.AudioProgressListener listener,
                                               boolean force) throws Exception {
        String sourcePath = normalizedSourcePath(videoFile);
        String videoId = stableVideoId(videoFile, sourcePath);
        AudioPackCatalog.Video cached = videos.get(videoId);
        String sourceHash = sha256(videoFile.toPath());
        if (!force && AudioCatalogPolicy.isReusable(cached, videoFile.length(),
            videoFile.lastModified(), sourceHash, chunkDurationMs, AudioExtractor.CACHE_FORMAT)) {
            return prepared(cached);
        }

        String suffix = chunkDurationMs == 0 ? "single" : chunkDurationMs + "ms";
        File audioDirectory = root.resolve("cache").resolve(videoId).resolve(suffix).toFile();
        AudioExtractor extractor = new AudioExtractor(plugin, videoFile, audioDirectory,
            chunkDurationMs, false, listener == null ? null
                : (stage, percent, detail) -> listener.onProgress(stage.description(), percent, detail));
        AudioExtractor.Result result = extractor.extract();
        List<AudioPackCatalog.Sound> sounds = result.chunks().stream()
            .sorted(Comparator.comparingInt(AudioManager.AudioChunk::index))
            .map(chunk -> new AudioPackCatalog.Sound(chunk.index(), chunk.startMs(), chunk.durationMs(),
                soundKey(videoId, chunk.index()), chunk.file()))
            .toList();
        AudioPackCatalog.Video entry = new AudioPackCatalog.Video(sourcePath, videoId,
            videoFile.length(), videoFile.lastModified(), sourceHash, AudioExtractor.CACHE_FORMAT,
            chunkDurationMs, sounds);
        return new PreparedVideo(entry, result.durationMs(), toChunks(entry));
    }

    private void publishCatalog(String reason) throws Exception {
        ensureRunning();
        state = State.BUILDING;
        AudioPackCatalog catalog = new AudioPackCatalog(Math.max(1, catalogVersion),
            new ArrayList<>(videos.values()));
        double radius = Math.max(1.0, plugin.getConfig().getDouble("audio.radius-blocks", 20.0));
        AudioPackBuilder.BuildResult result = null;
        try {
            result = AudioPackBuilder.build(catalog, root.resolve("published"), radius);
            String nextJavaHash = sha256(result.javaPack());
            String nextBedrockHash = sha256(result.bedrockPack());
            boolean bedrockPackChanged = !nextBedrockHash.equals(bedrockSha256);

            ResourcePackManager resourcePacks = plugin.getResourcePackManager();
            ResourcePackManager.HostedResourcePack hosted = null;
            if (resourcePacks != null) {
                String hostKey = "shared-java-" + nextJavaHash.substring(0, 16);
                hosted = resourcePacks.hostResourcePack(hostKey, result.javaPack().toFile(),
                    () -> plugin.getLogger().info("Uploading shared Java audio pack..."),
                    () -> plugin.getLogger().info("Waiting for shared Java audio pack host..."));
                if (hosted == null) {
                    throw new IOException("Shared Java pack could not be hosted");
                }
            }
            hostedJavaPack = hosted;
            bedrockPack = result.bedrockPack();
            javaSha256 = nextJavaHash;
            bedrockSha256 = nextBedrockHash;
            if (bedrockPackChanged) {
                bedrockGlobalRegistered = false;
            }
            javaSize = Files.size(result.javaPack());
            bedrockSize = Files.size(result.bedrockPack());
            lastFailure = "NONE";
            state = State.READY;
            saveManifest();
        } catch (Exception failure) {
            if (result != null) {
                try {
                    result.rollback();
                } catch (IOException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            state = State.FAILED;
            lastFailure = "Audio pack publish failed: " + failure.getMessage();
            throw failure;
        }

        if (javaSize > plugin.getConfig().getLong("audio.pack.warn-size-bytes", 100L * 1024L * 1024L)
            || bedrockSize > plugin.getConfig().getLong("audio.pack.warn-size-bytes", 100L * 1024L * 1024L)) {
            plugin.getLogger().warning("Shared audio pack is large (Java=" + javaSize
                + " bytes, Bedrock=" + bedrockSize + " bytes). Client download and memory pressure may increase.");
        }
        plugin.getLogger().info("Shared audio packs ready after " + reason + ": version="
            + catalogVersion + ", videos=" + videos.size() + ", sounds=" + catalog.soundCount()
            + ", Java SHA-256=" + javaSha256 + ", Bedrock SHA-256=" + bedrockSha256);

        updateBedrockReconnectRequired();
        plugin.refreshBedrockAudioPackRegistration();
        if (plugin.getConfig().getBoolean("audio.pack.send-updates-to-connected-java", false)) {
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getOnlinePlayers().forEach(this::sendJavaPack));
        }
    }

    public void playerJoined(Player player) {
        if (player == null || player.getUniqueId() == null) {
            return;
        }
        sendJavaPackWhenReady(player, 300);
    }

    private void sendJavaPackWhenReady(Player player, int attemptsRemaining) {
        if (shuttingDown || player == null || !player.isOnline() || player.getUniqueId() == null) {
            return;
        }
        AudioPackRouting.PackKind route = AudioPackRouting.packFor(
            plugin.getPlatformDetector().detect(player.getUniqueId()));
        if (route != AudioPackRouting.PackKind.JAVA) {
            return;
        }
        if (hostedJavaPack != null) {
            sendJavaPack(player);
            return;
        }
        if (attemptsRemaining > 0 && (state == State.EMPTY || state == State.BUILDING)) {
            Bukkit.getScheduler().runTaskLater(plugin,
                () -> sendJavaPackWhenReady(player, attemptsRemaining - 1), 20L);
        }
    }

    public void sendJavaPack(Player player) {
        UUID playerId = player == null ? null : player.getUniqueId();
        if (playerId == null || hostedJavaPack == null || javaSha256 == null
            || AudioPackRouting.packFor(plugin.getPlatformDetector().detect(playerId))
                != AudioPackRouting.PackKind.JAVA) {
            return;
        }
        byte[] clientHashBytes = hostedJavaPack.hash();
        if (clientHashBytes == null) {
            plugin.getLogger().warning("Cannot send shared Java audio pack without a client hash");
            return;
        }
        String clientHash = HexFormat.of().formatHex(clientHashBytes);
        String alreadySent = playerPackState.javaSent(playerId);
        if (javaSha256.equals(alreadySent)) {
            return;
        }
        boolean required = plugin.getConfig().getBoolean("resourcepack.required", false);
        String prompt = plugin.getConfig().getString("resourcepack.prompt",
            "MCCinema shared audio pack");
        player.addResourcePack(AudioPackBuilder.JAVA_PACK_ID, hostedJavaPack.url(), clientHashBytes,
            prompt, required);
        if (!playerPackState.markJavaSent(playerId, javaSha256, clientHash)) {
            return;
        }
        plugin.getViewerDiagnostics().setAudioMode(playerId,
            ViewerDiagnosticsService.AudioMode.JAVA_RESOURCE_PACK);
        plugin.getViewerDiagnostics().setResourcePackStatus(playerId, "SHARED_PACK_SENT");
    }

    public void onJavaPackStatus(Player player, org.bukkit.event.player.PlayerResourcePackStatusEvent.Status status,
                                 String eventHash) {
        UUID playerId = player == null ? null : player.getUniqueId();
        if (playerId == null || status == null) {
            return;
        }
        String sentVersion = playerPackState.javaSent(playerId);
        String expectedClientHash = playerPackState.javaSentClientHash(playerId);
        if (sentVersion == null || sentVersion.isBlank()) {
            return;
        }
        if (eventHash != null && !eventHash.isBlank() && expectedClientHash != null
            && !expectedClientHash.equalsIgnoreCase(eventHash)) {
            plugin.getLogger().warning("Ignored stale MCCinema resource-pack status for "
                + playerId + ": expected hash=" + expectedClientHash + ", event hash=" + eventHash);
            return;
        }
        switch (status) {
            case SUCCESSFULLY_LOADED -> {
                if (playerPackState.markJavaLoaded(playerId, sentVersion)) {
                    plugin.getViewerDiagnostics().setResourcePackStatus(playerId, "SHARED_PACK_LOADED");
                }
            }
            case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED ->
                playerPackState.clearJavaLoaded(playerId);
            case ACCEPTED -> {
                // The pack is not usable until SUCCESSFULLY_LOADED.
            }
        }
    }

    public boolean canPlayAudio(Player player) {
        UUID playerId = player == null ? null : player.getUniqueId();
        if (playerId == null) {
            return false;
        }
        PlayerPlatform platform = plugin.getPlatformDetector().detect(playerId);
        if (AudioPackRouting.packFor(platform) == AudioPackRouting.PackKind.JAVA) {
            return hostedJavaPack != null
                && javaSha256.equals(playerPackState.javaLoaded(playerId));
        }
        if (AudioPackRouting.packFor(platform) == AudioPackRouting.PackKind.BEDROCK) {
            return bedrockPack != null && playerPackState.isBedrockUsable(playerId, bedrockSha256);
        }
        return false;
    }

    public void playerDisconnected(UUID playerId) {
        playerPackState.disconnect(playerId);
        updateBedrockReconnectRequired();
    }

    public Status status() {
        return new Status(state, catalogVersion, videos.size(),
            videos.values().stream().mapToInt(video -> video.sounds().size()).sum(),
            javaSha256, javaSize, hostedJavaPack != null,
            bedrockSha256, bedrockSize, bedrockPack != null && Files.isRegularFile(bedrockPack),
            bedrockGlobalRegistered, bedrockReconnectRequired, lastFailure,
            bedrockSessions.pendingSessions(), playerPackState.authenticatedBedrockPlayers(),
            playerPackState.usableBedrockPlayers(bedrockSha256),
            failedVideos.stream().sorted().toList());
    }

    public Path bedrockPack() {
        return bedrockPack;
    }

    public void setBedrockGlobalRegistered(boolean registered) {
        this.bedrockGlobalRegistered = registered;
    }

    public BedrockPackSessionCoordinator.SessionResult attachBedrockPackToSession(
            Object connection, UUID earlyJavaUuid, boolean alreadyRegistered,
            BooleanSupplier registerPack) {
        return bedrockSessions.attachSession(connection, earlyJavaUuid, bedrockSha256,
            alreadyRegistered, registerPack);
    }

    public BedrockPackSessionCoordinator.JoinResult finalizeBedrockPackForPlayer(
            UUID playerId, Object connection) {
        BedrockPackSessionCoordinator.JoinResult result =
            bedrockSessions.completeJoin(playerId, connection, bedrockSha256);
        if (!result.authenticated()) {
            return result;
        }
        playerPackState.authenticateBedrock(playerId);
        if (result.usable()) {
            playerPackState.markBedrockLoaded(playerId, result.attachedVersion());
        } else {
            playerPackState.clearBedrockLoaded(playerId);
        }
        updateBedrockReconnectRequired();
        return result;
    }

    public void bedrockSessionDisconnected(Object connection) {
        bedrockSessions.disconnect(connection);
    }

    public boolean isBedrockPlayerAuthenticated(UUID playerId) {
        return playerPackState.isBedrockAuthenticated(playerId);
    }

    public void resetBedrockSessionAssociations() {
        bedrockSessions.clear();
        playerPackState.clearBedrock();
        updateBedrockReconnectRequired();
    }

    public void shutdown() {
        shuttingDown = true;
        bedrockSessions.clear();
        playerPackState.clear();
    }

    private void fail(String message, Exception failure) {
        state = State.FAILED;
        lastFailure = message;
        plugin.getLogger().severe(message);
        failure.printStackTrace();
    }

    private void ensureRunning() {
        if (shuttingDown) {
            throw new IllegalStateException("MCCinema is shutting down");
        }
    }

    private void recordVideoFailure(File videoFile, Exception failure) {
        clearVideoFailure(videoFile);
        failedVideos.add(videoFile.getName() + ": " + failure.getMessage());
    }

    private void clearVideoFailure(File videoFile) {
        String prefix = videoFile.getName() + ":";
        failedVideos.removeIf(entry -> entry.startsWith(prefix));
    }

    private void updateBedrockReconnectRequired() {
        bedrockReconnectRequired = playerPackState.hasStaleBedrockPlayer(bedrockSha256);
    }

    private void cleanupOrphanCaches() {
        Path cacheRoot = root.resolve("cache");
        if (!Files.isDirectory(cacheRoot)) {
            return;
        }
        try (var children = Files.list(cacheRoot)) {
            for (Path child : children.filter(Files::isDirectory).toList()) {
                if (!videos.containsKey(child.getFileName().toString())) {
                    deleteGeneratedCacheTree(child);
                    plugin.getLogger().info("Removed orphaned generated audio cache " + child.getFileName());
                }
            }
        } catch (IOException failure) {
            plugin.getLogger().warning("Could not clean orphaned generated audio caches: "
                + failure.getMessage());
        }
    }

    private static void deleteGeneratedCacheTree(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private void loadManifest() {
        YamlConfiguration manifest = YamlConfiguration.loadConfiguration(manifestFile);
        catalogVersion = manifest.getInt("version", 0);
        ConfigurationSection section = manifest.getConfigurationSection("videos");
        if (section == null) {
            return;
        }
        for (String videoId : section.getKeys(false)) {
            try {
                String base = "videos." + videoId;
                List<Map<?, ?>> rawSounds = manifest.getMapList(base + ".sounds");
                List<AudioPackCatalog.Sound> sounds = new ArrayList<>();
                for (Map<?, ?> raw : rawSounds) {
                    int index = ((Number) raw.get("index")).intValue();
                    sounds.add(new AudioPackCatalog.Sound(index,
                        ((Number) raw.get("expected-start-ms")).longValue(),
                        ((Number) raw.get("duration-ms")).longValue(),
                        String.valueOf(raw.get("key")),
                        root.resolve(String.valueOf(raw.get("ogg"))).toFile()));
                }
                AudioPackCatalog.Video video = new AudioPackCatalog.Video(
                    manifest.getString(base + ".source-path", ""), videoId,
                    manifest.getLong(base + ".source-size"), manifest.getLong(base + ".source-modified"),
                    manifest.getString(base + ".source-sha256", ""),
                    manifest.getString(base + ".cache-format", ""),
                    manifest.getInt(base + ".chunk-duration-ms"), sounds);
                if (sounds.stream().allMatch(sound -> sound.oggFile().isFile())) {
                    videos.put(videoId, video);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Ignoring invalid audio manifest entry " + videoId + ": " + e.getMessage());
            }
        }
    }

    private void saveManifest() throws IOException {
        YamlConfiguration manifest = new YamlConfiguration();
        manifest.set("version", catalogVersion);
        for (AudioPackCatalog.Video video : videos.values()) {
            String base = "videos." + video.videoId();
            manifest.set(base + ".source-path", video.sourcePath());
            manifest.set(base + ".source-size", video.sourceSize());
            manifest.set(base + ".source-modified", video.sourceModified());
            manifest.set(base + ".source-sha256", video.sourceSha256());
            manifest.set(base + ".cache-format", video.cacheFormat());
            manifest.set(base + ".chunk-duration-ms", video.chunkDurationMs());
            List<Map<String, Object>> sounds = new ArrayList<>();
            for (AudioPackCatalog.Sound sound : video.sounds()) {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("index", sound.index());
                value.put("expected-start-ms", sound.expectedStartMs());
                value.put("duration-ms", sound.durationMs());
                value.put("key", sound.key());
                value.put("ogg", root.relativize(sound.oggFile().toPath()).toString().replace('\\', '/'));
                sounds.add(value);
            }
            manifest.set(base + ".sounds", sounds);
        }
        Files.createDirectories(root);
        File temporary = root.resolve("manifest.yml.tmp").toFile();
        manifest.save(temporary);
        try {
            Files.move(temporary.toPath(), manifestFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary.toPath(), manifestFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private PreparedVideo prepared(AudioPackCatalog.Video video) {
        long duration = video.sounds().stream().mapToLong(sound -> sound.durationMs()).sum();
        return new PreparedVideo(video, duration, toChunks(video));
    }

    private static List<AudioManager.AudioChunk> toChunks(AudioPackCatalog.Video video) {
        return video.sounds().stream().map(sound -> new AudioManager.AudioChunk(sound.index(),
            sound.expectedStartMs(), sound.durationMs(), sound.oggFile())).toList();
    }

    private String normalizedSourcePath(File videoFile) throws IOException {
        Path data = plugin.getDataFolder().getCanonicalFile().toPath();
        Path source = videoFile.getCanonicalFile().toPath();
        return source.startsWith(data) ? data.relativize(source).toString().replace('\\', '/')
            : source.toString().replace('\\', '/');
    }

    static String stableVideoId(File videoFile, String normalizedPath) throws Exception {
        String name = videoFile.getName().replaceFirst("\\.[^.]+$", "")
            .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        if (name.isBlank()) {
            name = "video";
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String suffix = HexFormat.of().formatHex(digest.digest(
            normalizedPath.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8))).substring(0, 12);
        return name + "_" + suffix;
    }

    private static String soundKey(String videoId, int chunk) {
        return "mcc:" + videoId + ".chunk_" + chunk;
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static List<File> listVideos(File directory) {
        File[] files = directory.listFiles(file -> file.isFile() && VIDEO_EXTENSIONS.contains(extension(file)));
        if (files == null) {
            return List.of();
        }
        return java.util.Arrays.stream(files).sorted(Comparator.comparing(File::getName)).toList();
    }

    private static String extension(File file) {
        int dot = file.getName().lastIndexOf('.');
        return dot < 0 ? "" : file.getName().substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean catalogEquivalent(Collection<AudioPackCatalog.Video> left,
                                             Collection<AudioPackCatalog.Video> right) {
        if (left.size() != right.size()) {
            return false;
        }
        Map<String, AudioPackCatalog.Video> rightById = new LinkedHashMap<>();
        right.forEach(video -> rightById.put(video.videoId(), video));
        return left.stream().allMatch(video -> sameEntry(video, rightById.get(video.videoId())));
    }

    private static boolean sameEntry(AudioPackCatalog.Video left, AudioPackCatalog.Video right) {
        return left != null && right != null
            && left.sourceSize() == right.sourceSize()
            && left.sourceModified() == right.sourceModified()
            && left.sourceSha256().equals(right.sourceSha256())
            && left.cacheFormat().equals(right.cacheFormat())
            && left.chunkDurationMs() == right.chunkDurationMs()
            && left.sounds().stream().map(AudioPackCatalog.Sound::key).toList()
                .equals(right.sounds().stream().map(AudioPackCatalog.Sound::key).toList());
    }

    public record PreparedVideo(AudioPackCatalog.Video catalogEntry, long durationMs,
                                List<AudioManager.AudioChunk> chunks) {
        public String videoId() {
            return catalogEntry.videoId();
        }
    }

    public record Status(State state, int version, int videos, int sounds,
                         String javaSha256, long javaSize, boolean javaHosted,
                         String bedrockSha256, long bedrockSize, boolean bedrockReady,
                         boolean bedrockRegistered, boolean bedrockReconnectRequired,
                         String lastFailure, int bedrockPendingSessions,
                         int bedrockAuthenticatedPlayers, int bedrockUsablePlayers,
                         List<String> failedVideos) {
    }
}
