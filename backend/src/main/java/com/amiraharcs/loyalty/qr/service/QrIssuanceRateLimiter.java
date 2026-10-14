package com.amiraharcs.loyalty.qr.service;

import com.amiraharcs.loyalty.common.security.SlidingWindowRateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Per-instance limits, like the existing authentication budgets; no untrusted proxy headers. */
@Component
public class QrIssuanceRateLimiter {
    private final SlidingWindowRateLimiter customers;
    private final SlidingWindowRateLimiter addresses;
    public QrIssuanceRateLimiter(Clock clock) {
        customers = new SlidingWindowRateLimiter(clock, Duration.ofMinutes(1), 30);
        addresses = new SlidingWindowRateLimiter(clock, Duration.ofMinutes(1), 120);
    }
    public long retryAfter(UUID customerId) { return customers.reserve(customerId.toString()); }
    public long retryAfterAddress(String address) { return addresses.reserve(address); }
}
