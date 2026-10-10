package com.amiraharcs.loyalty.auth;

import com.amiraharcs.loyalty.auth.service.RegistrationRateLimiter;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RegistrationRateLimiterTests {
    @Test
    void appliesSlidingWindowAndKeepsAddressesIndependent() {
        Clock clock = mock(Clock.class);
        Instant start = Instant.parse("2026-10-10T00:00:00Z");
        when(clock.instant()).thenReturn(start);
        var limiter = new RegistrationRateLimiter(clock);
        for (int index = 0; index < 10; index++) { assertThat(limiter.retryAfter("one")).isZero(); }
        assertThat(limiter.retryAfter("one")).isEqualTo(3600);
        assertThat(limiter.retryAfter("two")).isZero();
        when(clock.instant()).thenReturn(start.plus(Duration.ofMinutes(30)));
        assertThat(limiter.retryAfter("one")).isEqualTo(1800);
        when(clock.instant()).thenReturn(start.plus(Duration.ofHours(1)));
        assertThat(limiter.retryAfter("one")).isZero();
    }

    @Test
    void concurrentRequestsCannotExceedTenAttempts() throws Exception {
        var limiter = new RegistrationRateLimiter(Clock.systemUTC());
        try (var threads = Executors.newFixedThreadPool(4)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Long>>();
            for (int index = 0; index < 20; index++) { futures.add(threads.submit(() -> limiter.retryAfter("one"))); }
            int allowed = 0;
            for (var future : futures) { if (future.get() == 0) { allowed++; } }
            assertThat(allowed).isEqualTo(10);
        }
    }
}
