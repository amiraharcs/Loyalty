package com.amiraharcs.loyalty.qr;

import com.amiraharcs.loyalty.qr.service.QrIssuanceRateLimiter;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class QrIssuanceRateLimiterTests {
    @Test void perCustomerThirtyPerMinuteResetsAtDeadlineAndBudgetsAreIndependent() {
        Clock clock = mock(Clock.class); Instant now = QrTokenCodecTests.NOW; when(clock.instant()).thenReturn(now);
        var limiter = new QrIssuanceRateLimiter(clock); var customer = UUID.randomUUID();
        for (int i=0;i<30;i++) { assertThat(limiter.retryAfter(customer)).isZero(); }
        assertThat(limiter.retryAfter(customer)).isEqualTo(60); assertThat(limiter.retryAfter(UUID.randomUUID())).isZero();
        when(clock.instant()).thenReturn(now.plusSeconds(59)); assertThat(limiter.retryAfter(customer)).isEqualTo(1);
        when(clock.instant()).thenReturn(now.plusSeconds(60)); assertThat(limiter.retryAfter(customer)).isZero();
    }
    @Test void ipBudgetIsIndependentAndBounded() {
        var limiter = new QrIssuanceRateLimiter(QrTokenCodecTests.CLOCK);
        for(int i=0;i<120;i++) { assertThat(limiter.retryAfterAddress("198.51.100.5")).isZero(); }
        assertThat(limiter.retryAfterAddress("198.51.100.5")).isEqualTo(60);
        assertThat(limiter.retryAfterAddress("198.51.100.6")).isZero();
    }
}
