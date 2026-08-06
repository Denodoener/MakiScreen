package de.erethon.mccinema.audio;

/** Pure cache policy shared by the catalog service and its regression tests. */
final class AudioCatalogPolicy {

    enum PlaybackDecision {
        REUSE_ACTIVE,
        VARIANT_NOT_ACTIVE,
        REBUILD_REQUIRED,
        PACK_NOT_READY;

        boolean playbackAllowed() {
            return this == REUSE_ACTIVE;
        }

        boolean packMutationRequired() {
            return this == REBUILD_REQUIRED || this == PACK_NOT_READY;
        }
    }

    private AudioCatalogPolicy() {
    }

    static boolean isReusable(AudioPackCatalog.Video cached, long sourceSize,
                              long sourceModified, String sourceSha256,
                              int chunkDurationMs, String cacheFormat) {
        return cached != null
            && cached.sourceSize() == sourceSize
            && cached.sourceModified() == sourceModified
            && cached.sourceSha256().equals(sourceSha256)
            && cached.chunkDurationMs() == chunkDurationMs
            && cached.cacheFormat().equals(cacheFormat)
            && !cached.sounds().isEmpty()
            && cached.sounds().stream().allMatch(sound -> sound.oggFile().isFile());
    }

    static PlaybackDecision playbackDecision(AudioPackCatalog.Video cached,
                                               long sourceSize, long sourceModified,
                                               String sourceSha256, int requestedChunkDurationMs,
                                               int configuredChunkDurationMs, String cacheFormat,
                                               boolean packReady) {
        if (requestedChunkDurationMs != configuredChunkDurationMs) {
            return PlaybackDecision.VARIANT_NOT_ACTIVE;
        }
        if (cached == null) {
            return PlaybackDecision.REBUILD_REQUIRED;
        }
        boolean sameSourceAndCache = cached.sourceSize() == sourceSize
            && cached.sourceModified() == sourceModified
            && cached.sourceSha256().equals(sourceSha256)
            && cached.cacheFormat().equals(cacheFormat)
            && !cached.sounds().isEmpty()
            && cached.sounds().stream().allMatch(sound -> sound.oggFile().isFile());
        if (sameSourceAndCache && cached.chunkDurationMs() != requestedChunkDurationMs) {
            return PlaybackDecision.VARIANT_NOT_ACTIVE;
        }
        if (!sameSourceAndCache) {
            return PlaybackDecision.REBUILD_REQUIRED;
        }
        return packReady ? PlaybackDecision.REUSE_ACTIVE : PlaybackDecision.PACK_NOT_READY;
    }
}
