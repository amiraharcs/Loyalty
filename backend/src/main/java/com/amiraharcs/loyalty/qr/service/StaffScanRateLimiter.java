package com.amiraharcs.loyalty.qr.service;

import com.amiraharcs.loyalty.common.security.SlidingWindowRateLimiter;
import com.amiraharcs.loyalty.common.api.RateLimitException;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class StaffScanRateLimiter {
    private final SlidingWindowRateLimiter staff;
    private final SlidingWindowRateLimiter addresses;
    public StaffScanRateLimiter(Clock clock) {
        staff = new SlidingWindowRateLimiter(clock, Duration.ofMinutes(1), 60);
        addresses = new SlidingWindowRateLimiter(clock, Duration.ofMinutes(1), 240);
    }
    public void reserveStaff(UUID id) { long retry = staff.reserve(id.toString()); if (retry > 0) { throw new RateLimitException(retry); } }
    public void reserveAddress(String address) { long retry = addresses.reserve(address); if (retry > 0) { throw new RateLimitException(retry); } }
}
