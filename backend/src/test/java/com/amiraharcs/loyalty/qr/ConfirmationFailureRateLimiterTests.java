package com.amiraharcs.loyalty.qr;

import com.amiraharcs.loyalty.qr.service.ConfirmationFailureRateLimiter;
import com.amiraharcs.loyalty.common.api.RateLimitException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfirmationFailureRateLimiterTests {
    @Test void failedReservationsExpireAndSuccessfulOperationsReleaseOnlyTheirReservations() {
        var clock=mock(Clock.class);var now=Instant.parse("2026-10-14T12:00:00Z");when(clock.instant()).thenReturn(now);
        var limiter=new ConfirmationFailureRateLimiter(clock,2,4);var actor=UUID.randomUUID();
        limiter.reserve(actor,"192.0.2.1");var successful=limiter.reserve(actor,"192.0.2.1");
        assertThatThrownBy(()->limiter.reserve(actor,"192.0.2.1")).isInstanceOfSatisfying(RateLimitException.class,ex->assertThat(ex.retryAfter()).isEqualTo(60));
        limiter.succeeded(successful);limiter.reserve(actor,"192.0.2.1");
        when(clock.instant()).thenReturn(now.plusSeconds(59));assertThatThrownBy(()->limiter.reserve(actor,"192.0.2.1"))
                .isInstanceOfSatisfying(RateLimitException.class,ex->assertThat(ex.retryAfter()).isEqualTo(1));
        when(clock.instant()).thenReturn(now.plusSeconds(60));limiter.reserve(actor,"192.0.2.1");
    }
    @Test void ipDenialDoesNotConsumeAnotherStaffBudgetAndBudgetsAreIndependent() {
        var limiter=new ConfirmationFailureRateLimiter(Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"),ZoneOffset.UTC),1,1);
        limiter.reserve(UUID.randomUUID(),"shared-ip");var other=UUID.randomUUID();
        assertThatThrownBy(()->limiter.reserve(other,"shared-ip")).isInstanceOf(RateLimitException.class);
        var success=limiter.reserve(other,"different-ip");assertThat(success.toString()).isEqualTo("ConfirmationFailureReservation[redacted]");limiter.succeeded(success);
        limiter.reserve(other,null);
    }
    @ParameterizedTest @CsvSource({"0,240","1001,240","60,0","60,1001"})
    void rejectsUnsafeConfiguration(int staff,int ip) {
        assertThatThrownBy(()->new ConfirmationFailureRateLimiter(Clock.systemUTC(),staff,ip)).isInstanceOf(IllegalArgumentException.class);
    }
}
