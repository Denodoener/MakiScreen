package de.erethon.mccinema.audio;

import java.util.Locale;

/** Selects the one chunk duration built into the shared audio pack. */
public final class AudioChunkDurationPolicy {

    private AudioChunkDurationPolicy() {
    }

    public static Selection select(int configuredDurationMs, String requestedArgument) {
        int configured = Math.max(0, configuredDurationMs);
        if (requestedArgument == null || requestedArgument.isBlank()) {
            return new Selection(true, configured, "NONE");
        }

        String requested = requestedArgument.toLowerCase(Locale.ROOT);
        Integer requestedDuration = parse(requested);
        if (requestedDuration == null) {
            return new Selection(false, configured,
                "Ungültige Audio-Chunkdauer: " + requestedArgument);
        }
        if (requestedDuration == configured) {
            return new Selection(true, configured, "NONE");
        }
        return new Selection(false, configured,
            "Das gemeinsame Audiopack verwendet " + describe(configured)
                + ". Ändere audio.chunk-duration-ms und führe /mcc audiopack rebuild aus.");
    }

    public static String argumentFor(int configuredDurationMs) {
        int configured = Math.max(0, configuredDurationMs);
        return configured == 0 ? "single" : Integer.toString(configured / 1000);
    }

    public static String describe(int durationMs) {
        if (durationMs == 0) {
            return "eine vorgebaute Single-Datei";
        }
        if (durationMs % 1000 == 0) {
            return (durationMs / 1000) + "-Sekunden-Chunks";
        }
        return durationMs + "-ms-Chunks";
    }

    private static Integer parse(String requested) {
        if ("single".equals(requested) || "0".equals(requested)) {
            return 0;
        }
        try {
            long seconds = Long.parseLong(requested);
            long milliseconds = Math.multiplyExact(seconds, 1000L);
            if (milliseconds <= 0L || milliseconds > Integer.MAX_VALUE) {
                return null;
            }
            return (int) milliseconds;
        } catch (NumberFormatException | ArithmeticException ignored) {
            return null;
        }
    }

    public record Selection(boolean accepted, int chunkDurationMs, String message) {
    }
}
