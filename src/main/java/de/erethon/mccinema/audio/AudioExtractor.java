/*
 * Reconstructed from the official MCCinema 2.3.3 release artifact because
 * this production source was missing from the upstream 2.3.3 tag.
 * MCCinema is licensed under GPL-3.0; see the repository LICENSE file.
 */
package de.erethon.mccinema.audio;

import de.erethon.mccinema.MCCinema;
import java.io.File;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_PCM_S16LE;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_VORBIS;
import static org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_FLTP;
import static org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16;

final class AudioExtractor {
    static final String CACHE_FORMAT = "pcm-wav-v2-validated";
    private final MCCinema plugin;
    private final File videoFile;
    private final File audioDir;
    private final int chunkDurationMs;
    private final boolean positionalAudio;
    private final int audioBitrate;
    private final int encoderThreads;
    private final ProgressListener progressListener;
    private final ProgressReporter progressReporter = new ProgressReporter();

    AudioExtractor(MCCinema plugin, File videoFile, File audioDir, int chunkDurationMs, boolean positionalAudio, ProgressListener progressListener) {
        this.plugin = plugin;
        this.videoFile = videoFile;
        this.audioDir = audioDir;
        this.chunkDurationMs = chunkDurationMs;
        this.positionalAudio = positionalAudio;
        this.audioBitrate = plugin.getConfig().getInt("audio.bitrate", 192000);
        this.encoderThreads = AudioExtractor.resolveEncoderThreads(plugin.getConfig().getInt("audio.conversion-threads", 0));
        this.progressListener = progressListener;
    }

    Result extract() throws Exception {
        Files.createDirectories(this.audioDir.toPath());
        File cacheMarker = new File(this.audioDir, ".extraction_complete");
        Result cached = this.loadCached(cacheMarker);
        if (cached != null) {
            this.report(Stage.CACHE, 100, cached.chunks().size() + " cached chunk(s)");
            return cached;
        }
        this.clearIncompleteOutput(cacheMarker);
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(this.videoFile);
        File fullAudio = new File(this.audioDir, "full_audio.ogg");
        try {
            List<AudioManager.AudioChunk> chunks;
            grabber.setSampleFormat(AV_SAMPLE_FMT_FLTP);
            grabber.start();
            if (grabber.getAudioChannels() == 0) {
                throw new IllegalArgumentException("Video has no audio track");
            }
            long durationMs = grabber.getLengthInTime() / 1000L;
            int sampleRate = grabber.getSampleRate();
            int sourceChannels = grabber.getAudioChannels();
            int outputChannels = this.positionalAudio ? 1 : Math.min(sourceChannels, 2);
            this.plugin.getLogger().info("Audio conversion: " + sampleRate + " Hz, " + sourceChannels + " source channel(s) -> " + outputChannels + " output channel(s)");
            this.extractFullAudio(grabber, fullAudio, sampleRate, outputChannels, durationMs);
            if (this.chunkDurationMs == 0) {
                File singleChunk = new File(this.audioDir, "chunk_0.ogg");
                Files.copy(fullAudio.toPath(), singleChunk.toPath(), StandardCopyOption.REPLACE_EXISTING);
                chunks = List.of(new AudioManager.AudioChunk(0, 0L, durationMs, singleChunk));
            } else {
                chunks = this.encodeChunks(fullAudio, sampleRate, outputChannels, durationMs);
            }
            AudioChunkValidator.Report validation = AudioChunkValidator.validate(
                chunks, durationMs, this.chunkDurationMs);
            for (AudioChunkValidator.ChunkProbe probe : validation.chunks()) {
                this.plugin.getLogger().info("Validated audio chunk " + probe.index()
                    + ": expected start=" + probe.expectedStartMs() + "ms, expected duration="
                    + probe.expectedDurationMs() + "ms, actual duration="
                    + probe.actualDurationMs() + "ms");
            }
            chunks = withActualDurations(chunks, validation);
            Files.writeString(cacheMarker.toPath(), CACHE_FORMAT + System.lineSeparator() + durationMs);
            this.report(Stage.COMPLETE, 100, chunks.size() + " chunk(s)");
            Result result = new Result(durationMs, chunks);
            return result;
        }
        finally {
            AudioExtractor.closeGrabber(grabber);
            Files.deleteIfExists(fullAudio.toPath());
        }
    }

    private Result loadCached(File cacheMarker) {
        if (!cacheMarker.exists()) {
            return null;
        }
        File[] chunkFiles = this.audioDir.listFiles((dir, name) -> name.startsWith("chunk_") && name.endsWith(".ogg"));
        if (chunkFiles == null || chunkFiles.length == 0) {
            return null;
        }
        try {
            List<String> markerLines = Files.readAllLines(cacheMarker.toPath());
            if (markerLines.size() != 2 || !CACHE_FORMAT.equals(((String)markerLines.getFirst()).trim())) {
                this.plugin.getLogger().info("Audio cache uses an outdated conversion format; rebuilding it");
                return null;
            }
            long durationMs = Long.parseLong(markerLines.get(1).trim());
            Arrays.sort(chunkFiles, Comparator.comparingInt(AudioExtractor::chunkIndex));
            List<AudioManager.AudioChunk> chunks = new ArrayList<AudioManager.AudioChunk>(chunkFiles.length);
            for (File chunkFile : chunkFiles) {
                int index = AudioExtractor.chunkIndex(chunkFile);
                long startMs = this.chunkDurationMs == 0 ? 0L : (long)index * (long)this.chunkDurationMs;
                long remainingMs = Math.max(0L, durationMs - startMs);
                long chunkLengthMs = this.chunkDurationMs == 0 ? durationMs : Math.min((long)this.chunkDurationMs, remainingMs);
                chunks.add(new AudioManager.AudioChunk(index, startMs, chunkLengthMs, chunkFile));
            }
            AudioChunkValidator.Report validation = AudioChunkValidator.validate(
                chunks, durationMs, this.chunkDurationMs);
            for (AudioChunkValidator.ChunkProbe probe : validation.chunks()) {
                this.plugin.getLogger().info("Validated cached audio chunk " + probe.index()
                    + ": expected start=" + probe.expectedStartMs() + "ms, actual duration="
                    + probe.actualDurationMs() + "ms");
            }
            chunks = withActualDurations(chunks, validation);
            return new Result(durationMs, chunks);
        }
        catch (Exception e) {
            this.plugin.getLogger().warning("Ignoring invalid audio cache: " + e.getMessage());
            return null;
        }
    }

    private void clearIncompleteOutput(File cacheMarker) throws Exception {
        Files.deleteIfExists(cacheMarker.toPath());
        File[] staleFiles = this.audioDir.listFiles((dir, name) -> name.startsWith("chunk_") || name.equals("full_audio.ogg") || name.equals("full_audio.wav"));
        if (staleFiles == null) {
            return;
        }
        for (File staleFile : staleFiles) {
            Files.deleteIfExists(staleFile.toPath());
        }
    }

    private void extractFullAudio(FFmpegFrameGrabber grabber, File outputFile, int sampleRate, int outputChannels, long durationMs) throws Exception {
        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(outputFile, 0, 0, outputChannels);
        try {
            Frame frame;
            recorder.setFormat("ogg");
            recorder.setAudioCodec(AV_CODEC_ID_VORBIS);
            recorder.setSampleRate(sampleRate);
            recorder.setAudioChannels(outputChannels);
            recorder.setAudioBitrate(this.audioBitrate);
            recorder.setSampleFormat(AV_SAMPLE_FMT_FLTP);
            recorder.setAudioOption("threads", "1");
            recorder.start();
            while ((frame = grabber.grabSamples()) != null) {
                if (frame.samples == null) continue;
                recorder.record(frame);
                int percent = AudioExtractor.percent(grabber.getTimestamp() / 1000L, durationMs);
                this.report(Stage.EXTRACTING, percent, AudioExtractor.formatTime(grabber.getTimestamp() / 1000L) + " / " + AudioExtractor.formatTime(durationMs));
            }
        }
        finally {
            AudioExtractor.closeRecorder(recorder);
        }
        this.report(Stage.EXTRACTING, 100, AudioExtractor.formatTime(durationMs) + " extracted");
    }

    private List<AudioManager.AudioChunk> encodeChunks(File fullAudio, int sampleRate, int channels, long durationMs) throws Exception {
        int samplesPerChunk = Math.max(1, (int)((long)sampleRate * (long)this.chunkDurationMs / 1000L));
        int expectedChunks = Math.max(1, (int)Math.ceil((double)durationMs / (double)this.chunkDurationMs));
        AtomicInteger encodedChunks = new AtomicInteger();
        AtomicInteger readPercent = new AtomicInteger();
        ThreadPoolExecutor executor = this.createEncoderExecutor();
        ArrayList<Future<AudioManager.AudioChunk>> futures = new ArrayList<Future<AudioManager.AudioChunk>>(expectedChunks);
        File fullWav = new File(this.audioDir, "full_audio.wav");
        this.convertToWav(fullAudio, fullWav, sampleRate, channels, durationMs);
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(fullWav);
        boolean inputComplete = false;
        try {
            Frame frame;
            grabber.setSampleFormat(AV_SAMPLE_FMT_S16);
            grabber.start();
            float[][] chunkBuffer = new float[channels][samplesPerChunk];
            int chunkBufferPosition = 0;
            int chunkIndex = 0;
            while ((frame = grabber.grabSamples()) != null) {
                int copiedSamples;
                float[][] frameSamples = this.extractSamplesAsFloat(frame, channels);
                if (frameSamples == null || frameSamples[0].length == 0) continue;
                int frameSampleCount = frameSamples[0].length;
                for (int framePosition = 0; framePosition < frameSampleCount; framePosition += copiedSamples) {
                    copiedSamples = Math.min(samplesPerChunk - chunkBufferPosition, frameSampleCount - framePosition);
                    for (int channel = 0; channel < channels; ++channel) {
                        System.arraycopy(frameSamples[channel], framePosition, chunkBuffer[channel], chunkBufferPosition, copiedSamples);
                    }
                    if ((chunkBufferPosition += copiedSamples) != samplesPerChunk) continue;
                    this.submitChunk(futures, executor, chunkBuffer, chunkBufferPosition, chunkIndex++, sampleRate, channels, encodedChunks, readPercent, expectedChunks);
                    chunkBuffer = new float[channels][samplesPerChunk];
                    chunkBufferPosition = 0;
                }
                readPercent.set(AudioExtractor.percent(grabber.getTimestamp() / 1000L, durationMs));
                this.reportChunkProgress(readPercent.get(), encodedChunks.get(), expectedChunks);
            }
            if (chunkBufferPosition >= sampleRate / 2 || chunkIndex == 0) {
                float[][] finalBuffer = AudioExtractor.trimBuffer(chunkBuffer, chunkBufferPosition, channels);
                this.submitChunk(futures, executor, finalBuffer, chunkBufferPosition, chunkIndex, sampleRate, channels, encodedChunks, readPercent, expectedChunks);
            }
            readPercent.set(100);
            inputComplete = true;
        }
        finally {
            AudioExtractor.closeGrabber(grabber);
            Files.deleteIfExists(fullWav.toPath());
            if (inputComplete) {
                executor.shutdown();
            } else {
                executor.shutdownNow();
            }
        }
        if (!executor.awaitTermination(1L, TimeUnit.HOURS)) {
            executor.shutdownNow();
            throw new IllegalStateException("Timed out while encoding audio chunks");
        }
        ArrayList<AudioManager.AudioChunk> chunks = new ArrayList<AudioManager.AudioChunk>(futures.size());
        for (Future<AudioManager.AudioChunk> future : futures) {
            chunks.add(future.get());
        }
        chunks.sort(Comparator.comparingInt(AudioManager.AudioChunk::index));
        this.report(Stage.CHUNKING, 100, chunks.size() + " chunk(s) encoded");
        return chunks;
    }

    private void submitChunk(List<Future<AudioManager.AudioChunk>> futures, ThreadPoolExecutor executor, float[][] buffer, int sampleCount, int chunkIndex, int sampleRate, int channels, AtomicInteger encodedChunks, AtomicInteger readPercent, int expectedChunks) {
        futures.add(executor.submit(() -> {
            AudioManager.AudioChunk chunk = this.encodeChunk(buffer, sampleCount, chunkIndex, sampleRate, channels);
            int completed = encodedChunks.incrementAndGet();
            this.reportChunkProgress(readPercent.get(), completed, expectedChunks);
            return chunk;
        }));
    }

    private AudioManager.AudioChunk encodeChunk(float[][] samples, int sampleCount, int chunkIndex, int sampleRate, int channels) throws Exception {
        File wavFile = new File(this.audioDir, "chunk_" + chunkIndex + ".wav");
        File outputFile = new File(this.audioDir, "chunk_" + chunkIndex + ".ogg");
        boolean complete = false;
        try {
            this.writeWavFromSamples(wavFile, samples, sampleRate, channels);
            this.convertWavToOgg(wavFile, outputFile, sampleRate, channels);
            complete = true;
        }
        finally {
            Files.deleteIfExists(wavFile.toPath());
            if (!complete) {
                Files.deleteIfExists(outputFile.toPath());
            }
        }
        long startMs = (long)chunkIndex * (long)this.chunkDurationMs;
        long actualDurationMs = (long)sampleCount * 1000L / (long)sampleRate;
        return new AudioManager.AudioChunk(chunkIndex, startMs, actualDurationMs, outputFile);
    }

    private void convertToWav(File inputFile, File outputFile, int sampleRate, int channels, long durationMs) throws Exception {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(inputFile);
        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(outputFile, 0, 0, channels);
        try {
            Frame frame;
            grabber.setSampleFormat(AV_SAMPLE_FMT_S16);
            grabber.start();
            recorder.setFormat("wav");
            recorder.setAudioCodec(AV_CODEC_ID_PCM_S16LE);
            recorder.setSampleRate(sampleRate);
            recorder.setAudioChannels(channels);
            recorder.setSampleFormat(AV_SAMPLE_FMT_S16);
            recorder.start();
            while ((frame = grabber.grabSamples()) != null) {
                if (frame.samples == null) continue;
                recorder.record(frame);
                this.report(Stage.CHUNKING, 0, "Preparing PCM audio " + AudioExtractor.formatTime(grabber.getTimestamp() / 1000L) + " / " + AudioExtractor.formatTime(durationMs));
            }
        }
        finally {
            AudioExtractor.closeRecorder(recorder);
            AudioExtractor.closeGrabber(grabber);
        }
    }

    private void writeWavFromSamples(File outputFile, float[][] samples, int sampleRate, int channels) throws Exception {
        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(outputFile, 0, 0, channels);
        try {
            recorder.setFormat("wav");
            recorder.setAudioCodec(AV_CODEC_ID_PCM_S16LE);
            recorder.setSampleRate(sampleRate);
            recorder.setAudioChannels(channels);
            recorder.setSampleFormat(AV_SAMPLE_FMT_S16);
            recorder.start();
            int samplesPerChannel = samples[0].length;
            for (int position = 0; position < samplesPerChannel; position += 1024) {
                int length = Math.min(1024, samplesPerChannel - position);
                short[] interleaved = new short[length * channels];
                for (int sampleIndex = 0; sampleIndex < length; ++sampleIndex) {
                    for (int channel = 0; channel < channels; ++channel) {
                        float sample = Math.max(-1.0f, Math.min(1.0f, samples[channel][position + sampleIndex]));
                        interleaved[sampleIndex * channels + channel] = (short)(sample * 32767.0f);
                    }
                }
                Frame frame = new Frame();
                frame.sampleRate = sampleRate;
                frame.audioChannels = channels;
                frame.samples = new Buffer[]{ShortBuffer.wrap(interleaved)};
                recorder.record(frame);
            }
        }
        finally {
            AudioExtractor.closeRecorder(recorder);
        }
    }

    private void convertWavToOgg(File wavFile, File oggFile, int sampleRate, int channels) throws Exception {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(wavFile);
        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(oggFile, 0, 0, channels);
        try {
            Frame frame;
            grabber.setSampleFormat(AV_SAMPLE_FMT_FLTP);
            grabber.start();
            recorder.setFormat("ogg");
            recorder.setAudioCodec(AV_CODEC_ID_VORBIS);
            recorder.setSampleRate(sampleRate);
            recorder.setAudioChannels(channels);
            recorder.setAudioBitrate(this.audioBitrate);
            recorder.setSampleFormat(AV_SAMPLE_FMT_FLTP);
            recorder.setAudioOption("flags", "+global_header");
            recorder.setAudioOption("threads", "1");
            recorder.setFrameNumber(0);
            recorder.start();
            while ((frame = grabber.grabSamples()) != null) {
                if (frame.samples == null) continue;
                recorder.record(frame);
            }
        }
        finally {
            AudioExtractor.closeGrabber(grabber);
            AudioExtractor.closeRecorder(recorder);
        }
    }

    private ThreadPoolExecutor createEncoderExecutor() {
        AtomicInteger threadNumber = new AtomicInteger();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(this.encoderThreads, this.encoderThreads, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(this.encoderThreads * 2), runnable -> {
            Thread thread = new Thread(runnable, "MCCinema-AudioEncoder-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(4);
            return thread;
        }, new ThreadPoolExecutor.CallerRunsPolicy());
        this.plugin.getLogger().info("Encoding audio chunks with " + this.encoderThreads + " worker thread(s)");
        return executor;
    }

    private float[][] extractSamplesAsFloat(Frame frame, int channels) {
        if (frame.samples == null || frame.samples.length == 0) {
            return null;
        }
        Buffer first = frame.samples[0];
        if (first instanceof ShortBuffer) {
            ShortBuffer shortBuffer = (ShortBuffer)first;
            ShortBuffer input = shortBuffer.duplicate();
            int samplesPerChannel = input.remaining() / channels;
            float[][] result = new float[channels][samplesPerChannel];
            for (int i = 0; i < samplesPerChannel; ++i) {
                for (int channel = 0; channel < channels; ++channel) {
                    result[channel][i] = (float)input.get() / 32768.0f;
                }
            }
            return result;
        }
        if (frame.samples.length < channels) {
            return null;
        }
        int samplesPerChannel = AudioExtractor.sampleCount(frame.samples[0]);
        float[][] result = new float[channels][samplesPerChannel];
        for (int channel = 0; channel < channels; ++channel) {
            FloatBuffer floatBuffer;
            Buffer plane = frame.samples[channel];
            if (plane instanceof FloatBuffer) {
                FloatBuffer floatBuffer2 = (FloatBuffer)plane;
                floatBuffer = floatBuffer2.duplicate();
            } else {
                floatBuffer = ((ByteBuffer)plane).duplicate().asFloatBuffer();
            }
            FloatBuffer input = floatBuffer;
            input.get(result[channel], 0, Math.min(input.remaining(), samplesPerChannel));
        }
        return result;
    }

    private static int sampleCount(Buffer buffer) {
        if (buffer instanceof FloatBuffer) {
            FloatBuffer floatBuffer = (FloatBuffer)buffer;
            return floatBuffer.remaining();
        }
        return buffer.remaining() / 4;
    }

    private static float[][] trimBuffer(float[][] source, int length, int channels) {
        float[][] trimmed = new float[channels][length];
        for (int channel = 0; channel < channels; ++channel) {
            System.arraycopy(source[channel], 0, trimmed[channel], 0, length);
        }
        return trimmed;
    }

    private void reportChunkProgress(int readPercent, int completedChunks, int expectedChunks) {
        int encodePercent = AudioExtractor.percent(completedChunks, expectedChunks);
        int overallPercent = (readPercent + encodePercent) / 2;
        this.report(Stage.CHUNKING, overallPercent, completedChunks + " / " + expectedChunks + " chunk(s) encoded");
    }

    private void report(Stage stage, int percent, String detail) {
        this.progressReporter.report(stage, Math.max(0, Math.min(100, percent)), detail);
    }

    private static int resolveEncoderThreads(int configuredThreads) {
        if (configuredThreads > 0) {
            return Math.max(1, Math.min(8, configuredThreads));
        }
        int logicalProcessors = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(4, logicalProcessors / 4));
    }

    private static int percent(long completed, long total) {
        if (total <= 0L) {
            return 0;
        }
        return (int)Math.min(100L, completed * 100L / total);
    }

    private static int chunkIndex(File file) {
        String name = file.getName();
        return Integer.parseInt(name.substring(6, name.length() - 4));
    }

    private static List<AudioManager.AudioChunk> withActualDurations(
            List<AudioManager.AudioChunk> chunks, AudioChunkValidator.Report report) {
        return chunks.stream().map(chunk -> {
            long actual = report.chunks().stream()
                .filter(probe -> probe.index() == chunk.index())
                .findFirst().map(AudioChunkValidator.ChunkProbe::actualDurationMs)
                .orElse(chunk.durationMs());
            return new AudioManager.AudioChunk(chunk.index(), chunk.startMs(), actual, chunk.file());
        }).toList();
    }

    private static String formatTime(long milliseconds) {
        long seconds = Math.max(0L, milliseconds / 1000L);
        return String.format("%d:%02d:%02d", seconds / 3600L, seconds % 3600L / 60L, seconds % 60L);
    }

    private static void closeGrabber(FFmpegFrameGrabber grabber) {
        try {
            grabber.stop();
        }
        catch (Exception exception) {
            // empty catch block
        }
        try {
            grabber.close();
        }
        catch (Exception exception) {
            // empty catch block
        }
    }

    private static void closeRecorder(FFmpegFrameRecorder recorder) {
        try {
            recorder.stop();
        }
        catch (Exception exception) {
            // empty catch block
        }
        try {
            recorder.close();
        }
        catch (Exception exception) {
            // empty catch block
        }
    }

    private final class ProgressReporter {
        private static final long HEARTBEAT_NANOS = TimeUnit.SECONDS.toNanos(1L);
        private Stage lastStage;
        private int lastPercent;
        private long lastReportNanos;
        private ProgressReporter() {
            this.lastPercent = -1;
        }

        synchronized void report(Stage stage, int percent, String detail) {
            if (progressListener == null) {
                return;
            }
            long now = System.nanoTime();
            boolean progressChanged = stage != this.lastStage || percent != this.lastPercent;
            if (!progressChanged && now - this.lastReportNanos < HEARTBEAT_NANOS) {
                return;
            }
            this.lastStage = stage;
            this.lastPercent = percent;
            this.lastReportNanos = now;
            progressListener.onProgress(stage, percent, detail);
        }
    }

    @FunctionalInterface
    interface ProgressListener {
        void onProgress(Stage stage, int percent, String detail);
    }

    record Result(long durationMs, List<AudioManager.AudioChunk> chunks) {
    }

    enum Stage {
        CACHE("Loading cached audio"),
        EXTRACTING("Extracting and downmixing audio"),
        CHUNKING("Encoding audio chunks"),
        COMPLETE("Audio conversion complete");

        private final String description;

        private Stage(String description) {
            this.description = description;
        }

        String description() {
            return this.description;
        }
    }
}
