package de.erethon.mccinema.audio;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds both client pack formats from the same catalog without mutating live packs. */
public final class AudioPackBuilder {

    public static final UUID JAVA_PACK_ID = UUID.fromString("46e24896-3d9c-55ee-8f48-f08c3a259b57");
    public static final UUID BEDROCK_PACK_ID = UUID.fromString("3d2bf4f4-73e9-5c45-b434-95394218c7da");
    public static final UUID BEDROCK_MODULE_ID = UUID.fromString("297f2d21-aec7-5be1-9282-d1b4f8c9cc98");

    private AudioPackBuilder() {
    }

    public static BuildResult build(AudioPackCatalog catalog, Path outputDirectory,
                                    double radiusBlocks) throws IOException {
        Files.createDirectories(outputDirectory);
        Path staging = Files.createTempDirectory(outputDirectory, ".audio-pack-build-");
        try {
            Path javaRoot = staging.resolve("java");
            Path bedrockRoot = staging.resolve("bedrock");
            buildJavaTree(catalog, javaRoot, radiusBlocks);
            buildBedrockTree(catalog, bedrockRoot, radiusBlocks);

            Path javaTemp = staging.resolve("mcc-audio-java.zip");
            Path bedrockTemp = staging.resolve("mcc-audio-bedrock.mcpack");
            zip(javaRoot, javaTemp);
            zip(bedrockRoot, bedrockTemp);

            Path javaPack = outputDirectory.resolve("mcc-audio-java.zip");
            Path bedrockPack = outputDirectory.resolve("mcc-audio-bedrock.mcpack");
            boolean priorJava = Files.exists(javaPack);
            boolean priorBedrock = Files.exists(bedrockPack);
            publishPair(javaTemp, javaPack, priorJava,
                bedrockTemp, bedrockPack, priorBedrock);
            return new BuildResult(javaPack, bedrockPack, catalog.version(),
                catalog.videos().size(), catalog.soundCount(), priorJava, priorBedrock);
        } finally {
            deleteTree(staging);
        }
    }

    static void buildJavaTree(AudioPackCatalog catalog, Path root, double radiusBlocks) throws IOException {
        Files.createDirectories(root);
        Files.writeString(root.resolve("pack.mcmeta"), """
            {
              "pack": {
                "min_format": 88,
                "max_format": 88,
                "description": "MCCinema shared audio catalog v%s"
              }
            }
            """.formatted(catalog.version()), StandardCharsets.UTF_8);

        StringBuilder soundsJson = new StringBuilder("{\n");
        List<AudioPackCatalog.Video> videos = sortedVideos(catalog);
        int written = 0;
        int total = catalog.soundCount();
        for (AudioPackCatalog.Video video : videos) {
            for (AudioPackCatalog.Sound sound : sortedSounds(video)) {
                Path destination = root.resolve("assets/mcc/sounds")
                    .resolve(video.videoId()).resolve("chunk_" + sound.index() + ".ogg");
                Files.createDirectories(destination.getParent());
                Files.copy(sound.oggFile().toPath(), destination, StandardCopyOption.REPLACE_EXISTING);

                String eventName = stripNamespace(sound.key());
                soundsJson.append("  \"").append(json(eventName)).append("\": {\n")
                    .append("    \"attenuation_distance\": ").append(radiusBlocks).append(",\n")
                    .append("    \"sounds\": [{\n")
                    .append("      \"name\": \"mcc:").append(json(video.videoId()))
                    .append("/chunk_").append(sound.index()).append("\",\n")
                    .append("      \"preload\": ").append(!video.singleFile()).append(",\n")
                    .append("      \"stream\": ").append(video.singleFile()).append("\n")
                    .append("    }]\n  }");
                if (++written < total) {
                    soundsJson.append(',');
                }
                soundsJson.append('\n');
            }
        }
        soundsJson.append("}\n");
        Path soundsFile = root.resolve("assets/mcc/sounds.json");
        Files.createDirectories(soundsFile.getParent());
        Files.writeString(soundsFile, soundsJson, StandardCharsets.UTF_8);
    }

    static void buildBedrockTree(AudioPackCatalog catalog, Path root, double radiusBlocks) throws IOException {
        Files.createDirectories(root);
        int revision = Math.max(1, catalog.version());
        String manifest = """
            {
              "format_version": 2,
              "header": {
                "name": "MCCinema shared audio",
                "description": "MCCinema native Bedrock audio catalog",
                "uuid": "%s",
                "version": [1, 0, %d],
                "min_engine_version": [1, 20, 0]
              },
              "modules": [{
                "type": "resources",
                "uuid": "%s",
                "version": [1, 0, %d]
              }]
            }
            """.formatted(BEDROCK_PACK_ID, revision, BEDROCK_MODULE_ID, revision);
        Files.writeString(root.resolve("manifest.json"), manifest, StandardCharsets.UTF_8);

        StringBuilder definitions = new StringBuilder(
            "{\n  \"format_version\": \"1.20.20\",\n  \"sound_definitions\": {\n");
        int written = 0;
        int total = catalog.soundCount();
        for (AudioPackCatalog.Video video : sortedVideos(catalog)) {
            for (AudioPackCatalog.Sound sound : sortedSounds(video)) {
                Path destination = root.resolve("sounds/mcc")
                    .resolve(video.videoId()).resolve("chunk_" + sound.index() + ".ogg");
                Files.createDirectories(destination.getParent());
                Files.copy(sound.oggFile().toPath(), destination, StandardCopyOption.REPLACE_EXISTING);

                definitions.append("    \"").append(json(sound.key())).append("\": {\n")
                    .append("      \"category\": \"music\",\n")
                    .append("      \"min_distance\": 1.0,\n")
                    .append("      \"max_distance\": ").append(radiusBlocks).append(",\n")
                    .append("      \"sounds\": [\"sounds/mcc/").append(json(video.videoId()))
                    .append("/chunk_").append(sound.index()).append("\"]\n")
                    .append("    }");
                if (++written < total) {
                    definitions.append(',');
                }
                definitions.append('\n');
            }
        }
        definitions.append("  }\n}\n");
        Path definitionsFile = root.resolve("sounds/sound_definitions.json");
        Files.createDirectories(definitionsFile.getParent());
        Files.writeString(definitionsFile, definitions, StandardCharsets.UTF_8);
    }

    private static List<AudioPackCatalog.Video> sortedVideos(AudioPackCatalog catalog) {
        return catalog.videos().stream().sorted(Comparator.comparing(AudioPackCatalog.Video::videoId)).toList();
    }

    private static List<AudioPackCatalog.Sound> sortedSounds(AudioPackCatalog.Video video) {
        return video.sounds().stream().sorted(Comparator.comparingInt(AudioPackCatalog.Sound::index)).toList();
    }

    private static String stripNamespace(String key) {
        return key.startsWith("mcc:") ? key.substring(4) : key;
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void zip(Path sourceRoot, Path destination) throws IOException {
        List<Path> files;
        try (var stream = Files.walk(sourceRoot)) {
            files = stream.filter(Files::isRegularFile).sorted().toList();
        }
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(destination)))) {
            for (Path file : files) {
                ZipEntry entry = new ZipEntry(sourceRoot.relativize(file).toString().replace('\\', '/'));
                entry.setTime(0L);
                zip.putNextEntry(entry);
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
    }

    private static void publishPair(Path firstStaged, Path firstLive, boolean priorFirst,
                                    Path secondStaged, Path secondLive, boolean priorSecond) throws IOException {
        backup(firstLive, priorFirst);
        backup(secondLive, priorSecond);
        try {
            move(firstStaged, firstLive);
            move(secondStaged, secondLive);
        } catch (IOException failure) {
            try {
                restorePair(firstLive, priorFirst, secondLive, priorSecond);
            } catch (IOException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    private static void backup(Path live, boolean existed) throws IOException {
        if (existed) {
            Files.copy(live, previous(live), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void move(Path staged, Path live) throws IOException {
        try {
            Files.move(staged, live, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(staged, live, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void restorePair(Path firstLive, boolean priorFirst,
                                    Path secondLive, boolean priorSecond) throws IOException {
        IOException failure = null;
        try {
            restore(firstLive, priorFirst);
        } catch (IOException e) {
            failure = e;
        }
        try {
            restore(secondLive, priorSecond);
        } catch (IOException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static void restore(Path live, boolean existed) throws IOException {
        if (existed) {
            Files.copy(previous(live), live, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.deleteIfExists(live);
        }
    }

    private static Path previous(Path live) {
        return live.resolveSibling(live.getFileName() + ".previous");
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> paths = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(paths::add);
        } catch (IOException ignored) {
            return;
        }
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // The live pack has already been published; a stale temp file is harmless.
            }
        }
    }

    public record BuildResult(Path javaPack, Path bedrockPack, int version, int videos, int sounds,
                              boolean priorJava, boolean priorBedrock) {
        public void rollback() throws IOException {
            restorePair(javaPack, priorJava, bedrockPack, priorBedrock);
        }
    }
}
