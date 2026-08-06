package de.erethon.mccinema.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioPackBuilderTest {

    @TempDir
    Path temp;

    @Test
    void sharedPacksContainEveryVideoWithoutKeyCollisions() throws Exception {
        AudioPackCatalog catalog = catalog(false);

        AudioPackBuilder.BuildResult result = AudioPackBuilder.build(catalog, temp.resolve("out"), 20.0);

        assertEquals(2, result.videos());
        assertEquals(3, result.sounds());
        try (ZipFile javaPack = new ZipFile(result.javaPack().toFile());
             ZipFile bedrockPack = new ZipFile(result.bedrockPack().toFile())) {
            assertNotNull(javaPack.getEntry("assets/mcc/sounds/video_a/chunk_0.ogg"));
            assertNotNull(javaPack.getEntry("assets/mcc/sounds/video_b/chunk_0.ogg"));
            assertNotNull(bedrockPack.getEntry("sounds/mcc/video_a/chunk_1.ogg"));
            String metadata = new String(javaPack.getInputStream(
                javaPack.getEntry("pack.mcmeta")).readAllBytes());
            assertTrue(metadata.contains("\"min_format\": 88"));
            assertTrue(metadata.contains("\"max_format\": 88"));
            String definitions = new String(bedrockPack.getInputStream(
                bedrockPack.getEntry("sounds/sound_definitions.json")).readAllBytes());
            assertTrue(definitions.contains("mcc:video_a.chunk_0"));
            assertTrue(definitions.contains("mcc:video_b.chunk_0"));
        }
    }

    @Test
    void singleFileUsesStreamingAndChunkedAudioDoesNot() throws Exception {
        AudioPackBuilder.BuildResult result = AudioPackBuilder.build(catalog(true), temp.resolve("out"), 20.0);

        try (ZipFile javaPack = new ZipFile(result.javaPack().toFile())) {
            String sounds = new String(javaPack.getInputStream(
                javaPack.getEntry("assets/mcc/sounds.json")).readAllBytes());
            String singleDefinition = sounds.substring(sounds.indexOf("video_b.chunk_0"));
            assertTrue(singleDefinition.contains("\"stream\": true"));
            assertTrue(singleDefinition.contains("\"preload\": false"));
            String chunkDefinition = sounds.substring(sounds.indexOf("video_a.chunk_0"),
                sounds.indexOf("video_a.chunk_1"));
            assertTrue(chunkDefinition.contains("\"stream\": false"));
            assertTrue(chunkDefinition.contains("\"preload\": true"));
        }
    }

    @Test
    void failedBuildKeepsPreviouslyPublishedPacks() throws Exception {
        Path output = temp.resolve("out");
        AudioPackBuilder.BuildResult first = AudioPackBuilder.build(catalog(false), output, 20.0);
        byte[] original = Files.readAllBytes(first.javaPack());
        AudioPackCatalog.Sound missing = new AudioPackCatalog.Sound(
            0, 0, 1000, "mcc:broken.chunk_0", temp.resolve("missing.ogg").toFile());
        AudioPackCatalog broken = new AudioPackCatalog(2, List.of(new AudioPackCatalog.Video(
            "broken.mp4", "broken", 1, 1, "hash", "v2", 1000, List.of(missing))));

        try {
            AudioPackBuilder.build(broken, output, 20.0);
        } catch (Exception expected) {
            // expected
        }

        assertTrue(Files.exists(first.javaPack()));
        assertTrue(java.util.Arrays.equals(original, Files.readAllBytes(first.javaPack())));
        assertFalse(Files.exists(output.resolve("mcc-audio-java.zip.tmp")));
    }

    @Test
    void publishedPairCanRollbackTogetherWhenHostingFails() throws Exception {
        Path output = temp.resolve("out");
        AudioPackBuilder.BuildResult first = AudioPackBuilder.build(catalog(false), output, 20.0);
        byte[] originalJava = Files.readAllBytes(first.javaPack());
        byte[] originalBedrock = Files.readAllBytes(first.bedrockPack());
        AudioPackCatalog changed = new AudioPackCatalog(2, catalog(false).videos());

        AudioPackBuilder.BuildResult replacement = AudioPackBuilder.build(changed, output, 20.0);
        replacement.rollback();

        assertTrue(java.util.Arrays.equals(originalJava, Files.readAllBytes(first.javaPack())));
        assertTrue(java.util.Arrays.equals(originalBedrock, Files.readAllBytes(first.bedrockPack())));
    }

    private AudioPackCatalog catalog(boolean secondIsSingle) throws Exception {
        Path first = Files.writeString(temp.resolve("first.ogg"), "first");
        Path second = Files.writeString(temp.resolve("second.ogg"), "second");
        Path third = Files.writeString(temp.resolve("third.ogg"), "third");
        AudioPackCatalog.Video videoA = new AudioPackCatalog.Video(
            "videos/a.mp4", "video_a", 10, 20, "a", "v2", 10_000, List.of(
                new AudioPackCatalog.Sound(0, 0, 10_000, "mcc:video_a.chunk_0", first.toFile()),
                new AudioPackCatalog.Sound(1, 10_000, 10_000, "mcc:video_a.chunk_1", second.toFile())));
        AudioPackCatalog.Video videoB = new AudioPackCatalog.Video(
            "videos/b.mp4", "video_b", 30, 40, "b", "v2", secondIsSingle ? 0 : 10_000, List.of(
                new AudioPackCatalog.Sound(0, 0, 20_000, "mcc:video_b.chunk_0", third.toFile())));
        return new AudioPackCatalog(1, List.of(videoA, videoB));
    }
}
