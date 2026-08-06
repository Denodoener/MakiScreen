package de.erethon.mccinema.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioCatalogPolicyTest {

    @TempDir
    Path temp;

    @Test
    void unchangedValidatedEntryIsReused() throws Exception {
        AudioPackCatalog.Video cached = cachedVideo(Files.writeString(temp.resolve("chunk.ogg"), "audio"));

        assertTrue(AudioCatalogPolicy.isReusable(cached, 100, 200, "source-hash",
            5_000, AudioExtractor.CACHE_FORMAT));
    }

    @Test
    void contentHashInvalidatesEvenWhenSizeAndTimestampMatch() throws Exception {
        AudioPackCatalog.Video cached = cachedVideo(Files.writeString(temp.resolve("chunk.ogg"), "audio"));

        assertFalse(AudioCatalogPolicy.isReusable(cached, 100, 200, "changed-content",
            5_000, AudioExtractor.CACHE_FORMAT));
    }

    @Test
    void cacheFormatAndMissingChunkInvalidateOnlyThatEntry() throws Exception {
        Path chunk = Files.writeString(temp.resolve("chunk.ogg"), "audio");
        AudioPackCatalog.Video cached = cachedVideo(chunk);
        Files.delete(chunk);

        assertFalse(AudioCatalogPolicy.isReusable(cached, 100, 200, "source-hash",
            5_000, AudioExtractor.CACHE_FORMAT));
        assertFalse(AudioCatalogPolicy.isReusable(cachedVideo(Files.writeString(chunk, "audio")),
            100, 200, "source-hash", 5_000, "older-format"));
    }

    private AudioPackCatalog.Video cachedVideo(Path chunk) {
        return new AudioPackCatalog.Video("videos/test.mp4", "test", 100, 200,
            "source-hash", AudioExtractor.CACHE_FORMAT, 5_000, List.of(
                new AudioPackCatalog.Sound(0, 0, 5_000, "mcc:test.chunk_0", chunk.toFile())));
    }
}
