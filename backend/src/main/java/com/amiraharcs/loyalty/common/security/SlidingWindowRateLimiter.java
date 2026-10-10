package com.amiraharcs.loyalty.common.security;

import java.time.*;
import java.util.*;

/** Bounded, thread-safe per-instance budget shared by registration and login. */
public final class SlidingWindowRateLimiter {
    private final Clock clock;
    private final Duration window;
    private final int limit;
    private final Map<String, ArrayDeque<Instant>> attempts = new HashMap<>();

    public SlidingWindowRateLimiter(Clock clock, Duration window, int limit) {
        if (limit < 1) { throw new IllegalArgumentException("Rate limit must be positive"); }
        this.clock = clock;
        this.window = window;
        this.limit = limit;
    }

    public long reserve(String key) { return reserveAttempt(key).retryAfter(); }

    /** Keep a reservation until failure, or release this one attempt after success. */
    public synchronized Reservation reserveAttempt(String key) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(window);
        attempts.values().forEach(times -> {
            while (!times.isEmpty() && !times.peekFirst().isAfter(cutoff)) { times.removeFirst(); }
        });
        attempts.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        var times = attempts.get(key);
        if (times == null) {
            if (attempts.size() >= 10_000) { return new Reservation(key, null, window.toSeconds()); }
            times = new ArrayDeque<>();
            attempts.put(key, times);
        }
        if (times.size() >= limit) {
            return new Reservation(key, null,
                    Math.max(1, (Duration.between(now, times.peekFirst().plus(window)).toMillis() + 999) / 1000));
        }
        times.addLast(now);
        return new Reservation(key, now, 0);
    }

    public synchronized void release(Reservation reservation) {
        var times = attempts.get(reservation.key());
        if (reservation.retryAfter() == 0 && times != null) {
            // Equal timestamps have equal expiry, so removing any one preserves the budget.
            times.removeFirstOccurrence(reservation.timestamp());
            if (times.isEmpty()) { attempts.remove(reservation.key()); }
        }
    }

    public record Reservation(String key, Instant timestamp, long retryAfter) { }
}
