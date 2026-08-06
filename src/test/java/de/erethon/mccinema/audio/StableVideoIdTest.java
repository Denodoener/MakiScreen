package de.erethon.mccinema.audio;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StableVideoIdTest {

    @Test
    void idIsStableAndPathCollisionSafe() throws Exception {
        File sameName = new File("movie.mp4");
        String first = AudioPackService.stableVideoId(sameName, "videos/first/movie.mp4");
        String repeated = AudioPackService.stableVideoId(sameName, "videos/first/movie.mp4");
        String collision = AudioPackService.stableVideoId(sameName, "videos/second/movie.mp4");

        assertEquals(first, repeated);
        assertNotEquals(first, collision);
        assertTrue(first.matches("movie_[0-9a-f]{12}"));
    }
}
