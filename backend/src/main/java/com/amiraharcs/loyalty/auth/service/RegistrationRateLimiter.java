package com.amiraharcs.loyalty.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Bounded per-instance sliding window: the architecture allows ten registration attempts/hour/IP. */
@Component
public class RegistrationRateLimiter {
    private static final Duration WINDOW = Duration.ofHours(1);
    private static final int LIMIT = 10;
    private static final int MAX_ADDRESSES = 10_000;
    private final Map<String, ArrayDeque<Instant>> attempts = new HashMap<>();
    private final Clock clock;

    public RegistrationRateLimiter(Clock clock) { this.clock = clock; }

    /** Zero permits an attempt; otherwise return Retry-After seconds. */
    public synchronized long retryAfter(String address) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(WINDOW);
        attempts.values().forEach(times -> {
            while (!times.isEmpty() && !times.peekFirst().isAfter(cutoff)) { times.removeFirst(); }
        });
        attempts.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        var times = attempts.get(address);
        if (times == null) {
            if (attempts.size() >= MAX_ADDRESSES) { return WINDOW.toSeconds(); }
            times = new ArrayDeque<>();
            attempts.put(address, times);
        }
        if (times.size() >= LIMIT) {
            return Math.max(1, (Duration.between(now, times.peekFirst().plus(WINDOW)).toMillis() + 999) / 1000);
        }
        times.addLast(now);
        return 0;
    }
}
