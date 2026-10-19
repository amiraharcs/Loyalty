package com.amiraharcs.loyalty.customer.service;

import com.amiraharcs.loyalty.common.api.RateLimitException;
import com.amiraharcs.loyalty.common.security.SlidingWindowRateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Per-instance budgets count all authenticated attempts, including malformed/no-match requests. */
@Component
public class CustomerLookupRateLimiter {
    private final SlidingWindowRateLimiter staff;
    private final SlidingWindowRateLimiter addresses;
    public CustomerLookupRateLimiter(Clock clock, @Value("${loyalty.customer-lookup.staff-limit:30}") int staffLimit,
            @Value("${loyalty.customer-lookup.ip-limit:120}") int ipLimit) {
        if (staffLimit < 1 || staffLimit > 1000 || ipLimit < 1 || ipLimit > 1000) {
            throw new IllegalArgumentException("Lookup limits must be in 1..1000");
        }
        staff = new SlidingWindowRateLimiter(clock, Duration.ofMinutes(1), staffLimit);
        addresses = new SlidingWindowRateLimiter(clock, Duration.ofMinutes(1), ipLimit);
    }
    public void reserve(UUID actor, String address) {
        long retry = addresses.reserve(address);
        if (retry > 0) { throw new RateLimitException(retry); }
        retry = staff.reserve(actor.toString());
        if (retry > 0) { throw new RateLimitException(retry); }
    }
}
