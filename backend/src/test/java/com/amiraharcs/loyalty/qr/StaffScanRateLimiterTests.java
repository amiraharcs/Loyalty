package com.amiraharcs.loyalty.qr;

import com.amiraharcs.loyalty.qr.service.StaffScanRateLimiter;
import com.amiraharcs.loyalty.common.api.RateLimitException;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StaffScanRateLimiterTests {
    @Test void staffBudgetResetsAtTheMinuteAndIpBudgetIsIndependent() {
        var clock=mock(Clock.class);var now=Instant.parse("2026-10-14T12:00:00Z");when(clock.instant()).thenReturn(now);
        var limiter=new StaffScanRateLimiter(clock);var staff=UUID.randomUUID();
        for(int i=0;i<60;i++){limiter.reserveStaff(staff);}
        assertThatThrownBy(()->limiter.reserveStaff(staff)).isInstanceOfSatisfying(RateLimitException.class,ex->assertThat(ex.retryAfter()).isEqualTo(60));
        limiter.reserveStaff(UUID.randomUUID());for(int i=0;i<240;i++){limiter.reserveAddress("192.0.2.4");}
        assertThatThrownBy(()->limiter.reserveAddress("192.0.2.4")).isInstanceOf(RateLimitException.class);limiter.reserveAddress("192.0.2.5");
        when(clock.instant()).thenReturn(now.plusSeconds(60));limiter.reserveStaff(staff);limiter.reserveAddress("192.0.2.4");
    }
}
