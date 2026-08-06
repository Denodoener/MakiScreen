package de.erethon.mccinema.audio;

import org.bytedeco.javacv.FFmpegFrameGrabber;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** ffprobe-equivalent validation using the already bundled JavaCV/FFmpeg runtime. */
final class AudioChunkValidator {

    private AudioChunkValidator() {
    }

    static Report validate(List<AudioManager.AudioChunk> chunks, long sourceDurationMs,
                           int chunkDurationMs) throws Exception {
        if (chunks.isEmpty()) {
            throw new IllegalStateException("Audio extraction produced no chunks");
        }
        List<ChunkProbe> probes = new ArrayList<>(chunks.size());
        long total = 0;
        for (AudioManager.AudioChunk chunk : chunks) {
            long actual = probeDuration(chunk.file());
            total += actual;
            probes.add(new ChunkProbe(chunk.index(), chunk.startMs(), chunk.durationMs(), actual,
                sha256(chunk.file())));
            if (chunkDurationMs > 0 && chunks.size() > 1
                && actual >= Math.max(sourceDurationMs - 750L, chunkDurationMs * 2L)) {
                throw new IllegalStateException("Chunk " + chunk.index()
                    + " appears to contain the full source (" + actual + "ms)");
            }
        }

        long allowedDifference = Math.max(1_500L, chunks.size() * 250L);
        if (Math.abs(total - sourceDurationMs) > allowedDifference) {
            throw new IllegalStateException("Combined chunk duration " + total
                + "ms differs from source " + sourceDurationMs + "ms by more than "
                + allowedDifference + "ms");
        }
        if (probes.size() > 1 && probes.get(0).sha256().equals(probes.get(1).sha256())) {
            throw new IllegalStateException("chunk_0 and chunk_1 are byte-identical");
        }
        return new Report(sourceDurationMs, total, probes);
    }

    private static long probeDuration(File file) throws Exception {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(file);
        try {
            grabber.start();
            return Math.max(0L, grabber.getLengthInTime() / 1000L);
        } finally {
            try {
                grabber.stop();
            } catch (Exception ignored) {
            }
            grabber.close();
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    record ChunkProbe(int index, long expectedStartMs, long expectedDurationMs,
                      long actualDurationMs, String sha256) {
    }

    record Report(long sourceDurationMs, long combinedDurationMs, List<ChunkProbe> chunks) {
        Report {
            chunks = List.copyOf(chunks);
        }
    }
}
