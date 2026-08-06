package de.erethon.mccinema.audio;

/** Pure cache policy shared by the catalog service and its regression tests. */
final class AudioCatalogPolicy {

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
}
