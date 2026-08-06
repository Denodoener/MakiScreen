package de.erethon.mccinema.audio;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Thread-safe one-shot gate shared by success and timeout completion paths. */
public final class CompletionGate {

    private final AtomicBoolean completed = new AtomicBoolean();
    private final AtomicInteger attempts = new AtomicInteger();

    public boolean tryComplete() {
        attempts.incrementAndGet();
        return completed.compareAndSet(false, true);
    }

    public int attempts() {
        return attempts.get();
    }

    public boolean completed() {
        return completed.get();
    }
}
