package de.erethon.mccinema.audio;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletionGateTest {

    @Test
    void callbackAndTimeoutCanCompleteOnlyOnce() throws Exception {
        CompletionGate gate = new CompletionGate();
        AtomicInteger starts = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Runnable completion = () -> {
            ready.countDown();
            try {
                go.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (gate.tryComplete()) {
                starts.incrementAndGet();
            }
        };
        Thread callback = Thread.ofPlatform().start(completion);
        Thread timeout = Thread.ofPlatform().start(completion);
        ready.await();
        go.countDown();
        callback.join();
        timeout.join();

        assertEquals(1, starts.get());
        assertEquals(2, gate.attempts());
        assertTrue(gate.completed());
    }
}
