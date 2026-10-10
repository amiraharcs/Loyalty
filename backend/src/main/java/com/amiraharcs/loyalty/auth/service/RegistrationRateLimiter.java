package com.amiraharcs.loyalty.auth.service;

import com.amiraharcs.loyalty.common.security.SlidingWindowRateLimiter;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class RegistrationRateLimiter {
    private final SlidingWindowRateLimiter budget;
    public RegistrationRateLimiter(Clock clock) { budget = new SlidingWindowRateLimiter(clock, Duration.ofHours(1), 10); }
    public long retryAfter(String address) { return budget.reserve(address); }
}
