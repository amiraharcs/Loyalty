package com.amiraharcs.loyalty.auth;

import com.amiraharcs.loyalty.auth.service.LoginRateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoginRateLimiterTests {
    @Test
    void accountFailuresExpireWithoutPermanentLockout() {
        Clock clock = mock(Clock.class);
        Instant start = Instant.parse("2026-10-10T00:00:00Z");
        when(clock.instant()).thenReturn(start);
        var limiter = new LoginRateLimiter(clock, 50);
        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(limiter.reserveAccount("member@example.org", "one").retryAfter()).isZero();
        }
        assertThat(limiter.reserveAccount("member@example.org", "one").retryAfter()).isEqualTo(900);
        assertThat(limiter.reserveAccount("member@example.org", "two").retryAfter()).isZero();
        assertThat(limiter.reserveAccount("other@example.org", "one").retryAfter()).isZero();
        when(clock.instant()).thenReturn(start.plus(Duration.ofMinutes(15)));
        assertThat(limiter.reserveAccount("member@example.org", "one").retryAfter()).isZero();
    }

    @Test
    void successfulAttemptsDoNotConsumeBudgetOrClearEarlierFailures() {
        var limiter = new LoginRateLimiter(Clock.systemUTC(), 50);
        for (int index = 0; index < 20; index++) {
            var attempt = limiter.reserveAccount("member@example.org", "one");
            assertThat(attempt.retryAfter()).isZero();
            limiter.succeeded(attempt);
        }
        for (int index = 0; index < 9; index++) {
            assertThat(limiter.reserveAccount("member@example.org", "one").retryAfter()).isZero();
        }
        var success = limiter.reserveAccount("member@example.org", "one");
        assertThat(success.retryAfter()).isZero();
        limiter.succeeded(success);
        assertThat(limiter.reserveAccount("member@example.org", "one").retryAfter()).isZero();
        assertThat(limiter.reserveAccount("member@example.org", "one").retryAfter()).isPositive();
    }

    @Test
    void independentAddressBudgetSurvivesSuccessfulLoginsAndRejectsInvalidConfiguration() {
        var limiter = new LoginRateLimiter(Clock.systemUTC(), 3);
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(limiter.reserveAddress("one")).isZero();
            limiter.succeeded(limiter.reserveAccount("member@example.org", "one"));
        }
        assertThat(limiter.reserveAddress("one")).isPositive();
        assertThat(limiter.reserveAddress("two")).isZero();
        assertThatThrownBy(() -> new LoginRateLimiter(Clock.systemUTC(), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginRateLimiter(Clock.systemUTC(), 1001)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentCredentialChecksCannotExceedTheAccountBudget() throws Exception {
        var limiter = new LoginRateLimiter(Clock.systemUTC(), 50);
        try (var threads = Executors.newFixedThreadPool(4)) {
            var results = new java.util.ArrayList<java.util.concurrent.Future<Long>>();
            for (int attempt = 0; attempt < 20; attempt++) {
                results.add(threads.submit(() -> limiter.reserveAccount("member@example.org", "one").retryAfter()));
            }
            int allowed = 0;
            for (var result : results) { if (result.get() == 0) { allowed++; } }
            assertThat(allowed).isEqualTo(10);
        }
    }
}
