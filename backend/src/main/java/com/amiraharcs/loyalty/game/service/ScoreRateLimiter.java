package com.amiraharcs.loyalty.game.service;

import com.amiraharcs.loyalty.common.security.SlidingWindowRateLimiter;
import com.amiraharcs.loyalty.common.api.RateLimitException;
import java.time.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Counts every authenticated, well-formed attempt, including retries; no permanent lockout. */
@Component
public class ScoreRateLimiter {
    private final SlidingWindowRateLimiter customers;
    private final SlidingWindowRateLimiter addresses;
    public ScoreRateLimiter(Clock clock,@Value("${loyalty.game-scores.customer-limit:60}") int customerLimit,
            @Value("${loyalty.game-scores.ip-limit:240}") int ipLimit) {
        if(customerLimit<1 || customerLimit>1000 || ipLimit<1 || ipLimit>1000) {throw new IllegalArgumentException("Score limits must be in 1..1000");}
        customers=new SlidingWindowRateLimiter(clock,Duration.ofMinutes(1),customerLimit);
        addresses=new SlidingWindowRateLimiter(clock,Duration.ofMinutes(1),ipLimit);
    }
    public void reserve(UUID actor,String address) {
        long ip=addresses.reserve(address);if(ip>0) {throw new RateLimitException(ip);}
        long customer=customers.reserve(actor.toString());if(customer>0) {throw new RateLimitException(customer);}
    }
}
