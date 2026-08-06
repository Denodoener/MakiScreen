package de.erethon.mccinema.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void unchangedVideoCanBePlayedTwiceWithoutPackMutationOrUpload() throws Exception {
        AudioPackCatalog.Video cached = cachedVideo(Files.writeString(temp.resolve("chunk.ogg"), "audio"));

        AudioCatalogPolicy.PlaybackDecision first = playbackDecision(cached, 5_000, true);
        AudioCatalogPolicy.PlaybackDecision second = playbackDecision(cached, 5_000, true);

        assertEquals(AudioCatalogPolicy.PlaybackDecision.REUSE_ACTIVE, first);
        assertEquals(AudioCatalogPolicy.PlaybackDecision.REUSE_ACTIVE, second);
        assertTrue(first.playbackAllowed());
        assertFalse(first.packMutationRequired());
        assertFalse(second.packMutationRequired());
    }

    @Test
    void switchingBetweenPreparedVideosDoesNotBuildOrPublishAPack() throws Exception {
        AudioPackCatalog.Video first = cachedVideo(Files.writeString(temp.resolve("first.ogg"), "first"));
        AudioPackCatalog.Video second = cachedVideo(Files.writeString(temp.resolve("second.ogg"), "second"));

        assertFalse(playbackDecision(first, 5_000, true).packMutationRequired());
        assertFalse(playbackDecision(second, 5_000, true).packMutationRequired());
    }

    @Test
    void inactiveVariantIsRejectedInsteadOfReplacingSharedPack() throws Exception {
        AudioPackCatalog.Video cached = cachedVideo(Files.writeString(temp.resolve("chunk.ogg"), "audio"));

        AudioCatalogPolicy.PlaybackDecision decision = playbackDecision(cached, 10_000, true);

        assertEquals(AudioCatalogPolicy.PlaybackDecision.VARIANT_NOT_ACTIVE, decision);
        assertFalse(decision.playbackAllowed());
        assertFalse(decision.packMutationRequired());
    }

    @Test
    void changedVideoRequiresBuildButCannotStartCurrentAudioPlayback() throws Exception {
        AudioPackCatalog.Video cached = cachedVideo(Files.writeString(temp.resolve("chunk.ogg"), "audio"));

        AudioCatalogPolicy.PlaybackDecision decision = AudioCatalogPolicy.playbackDecision(
            cached, 100, 200, "changed-source", 5_000, 5_000,
            AudioExtractor.CACHE_FORMAT, true);

        assertEquals(AudioCatalogPolicy.PlaybackDecision.REBUILD_REQUIRED, decision);
        assertTrue(decision.packMutationRequired());
        assertFalse(decision.playbackAllowed());
    }

    @Test
    void catalogBuildAndPlaybackAdmissionRemainSeparate() throws Exception {
        AudioPackCatalog.Video cached = cachedVideo(Files.writeString(temp.resolve("chunk.ogg"), "audio"));

        AudioCatalogPolicy.PlaybackDecision decision = playbackDecision(cached, 5_000, false);

        assertEquals(AudioCatalogPolicy.PlaybackDecision.PACK_NOT_READY, decision);
        assertTrue(decision.packMutationRequired());
        assertFalse(decision.playbackAllowed());
    }

    private AudioCatalogPolicy.PlaybackDecision playbackDecision(AudioPackCatalog.Video cached,
                                                                  int requestedDurationMs,
                                                                  boolean packReady) {
        return AudioCatalogPolicy.playbackDecision(cached, 100, 200, "source-hash",
            requestedDurationMs, 5_000, AudioExtractor.CACHE_FORMAT, packReady);
    }

    private AudioPackCatalog.Video cachedVideo(Path chunk) {
        return new AudioPackCatalog.Video("videos/test.mp4", "test", 100, 200,
            "source-hash", AudioExtractor.CACHE_FORMAT, 5_000, List.of(
                new AudioPackCatalog.Sound(0, 0, 5_000, "mcc:test.chunk_0", chunk.toFile())));
    }
}
