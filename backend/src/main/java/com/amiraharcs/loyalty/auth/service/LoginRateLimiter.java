package com.amiraharcs.loyalty.auth.service;

import com.amiraharcs.loyalty.common.security.SlidingWindowRateLimiter;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LoginRateLimiter {
    private final SlidingWindowRateLimiter addresses;
    private final SlidingWindowRateLimiter accounts;

    public LoginRateLimiter(Clock clock, @Value("${LOGIN_IP_ATTEMPT_LIMIT:50}") int ipLimit) {
        if (ipLimit < 1 || ipLimit > 1000) { throw new IllegalArgumentException("LOGIN_IP_ATTEMPT_LIMIT must be between 1 and 1000"); }
        addresses = new SlidingWindowRateLimiter(clock, Duration.ofMinutes(15), ipLimit);
        accounts = new SlidingWindowRateLimiter(clock, Duration.ofMinutes(15), 10);
    }
    public long reserveAddress(String address) { return addresses.reserve(address); }
    public SlidingWindowRateLimiter.Reservation reserveAccount(String email, String address) {
        return accounts.reserveAttempt(key(email, address));
    }
    public void succeeded(SlidingWindowRateLimiter.Reservation attempt) { accounts.release(attempt); }

    private static String key(String email, String address) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((email + "\u0000" + address).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable"); }
    }
}
