package com.amiraharcs.loyalty.qr.service;

import com.amiraharcs.loyalty.common.api.RateLimitException;
import com.amiraharcs.loyalty.common.security.SlidingWindowRateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Temporary failed-confirmation budgets. Successful confirmations/retries release their own reservations. */
@Component
public class ConfirmationFailureRateLimiter {
    private final SlidingWindowRateLimiter staff;
    private final SlidingWindowRateLimiter addresses;
    public ConfirmationFailureRateLimiter(Clock clock,
            @Value("${loyalty.qr.confirmation-failure-limit:60}") int staffLimit,
            @Value("${loyalty.qr.confirmation-ip-failure-limit:240}") int ipLimit) {
        if (staffLimit<1 || staffLimit>1000 || ipLimit<1 || ipLimit>1000) {
            throw new IllegalArgumentException("QR confirmation failure limits must be between 1 and 1000");
        }
        staff=new SlidingWindowRateLimiter(clock,Duration.ofMinutes(1),staffLimit);
        addresses=new SlidingWindowRateLimiter(clock,Duration.ofMinutes(1),ipLimit);
    }
    public Reservation reserve(UUID staffId,String address) {
        var actor=staff.reserveAttempt(staffId.toString());
        if (actor.retryAfter()>0) { throw new RateLimitException(actor.retryAfter()); }
        var ip=address==null ? null : addresses.reserveAttempt(address);
        if (ip!=null && ip.retryAfter()>0) { staff.release(actor);throw new RateLimitException(ip.retryAfter()); }
        return new Reservation(actor,ip);
    }
    public void succeeded(Reservation reservation) {
        staff.release(reservation.staff());
        if (reservation.address()!=null) { addresses.release(reservation.address()); }
    }
    public record Reservation(SlidingWindowRateLimiter.Reservation staff,SlidingWindowRateLimiter.Reservation address) {
        @Override public String toString() { return "ConfirmationFailureReservation[redacted]"; }
    }
}
