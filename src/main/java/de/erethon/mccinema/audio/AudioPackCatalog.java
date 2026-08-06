package de.erethon.mccinema.audio;

import java.io.File;
import java.util.List;

/** Immutable input used to build the shared Java and Bedrock audio packs. */
public record AudioPackCatalog(int version, List<Video> videos) {

    public AudioPackCatalog {
        videos = List.copyOf(videos);
    }

    public int soundCount() {
        return videos.stream().mapToInt(video -> video.sounds().size()).sum();
    }

    public record Video(
        String sourcePath,
        String videoId,
        long sourceSize,
        long sourceModified,
        String sourceSha256,
        String cacheFormat,
        int chunkDurationMs,
        List<Sound> sounds
    ) {
        public Video {
            sounds = List.copyOf(sounds);
        }

        public boolean singleFile() {
            return chunkDurationMs == 0;
        }
    }

    public record Sound(int index, long expectedStartMs, long durationMs, String key, File oggFile) {
    }
}
