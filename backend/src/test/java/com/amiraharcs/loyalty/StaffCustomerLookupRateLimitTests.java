package com.amiraharcs.loyalty;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

class StaffCustomerLookupRateLimitTests extends StaffCustomerLookupTestSupport {
    @Test void staffLimitIs429AuditedAndRecoversAfterOneMinute() throws Exception {
        var s=lookupSetup();for(int i=0;i<30;i++) {lookupLimiter.reserve(s.staff().userId,s.staff().ip);}
        var before=state(s.identifier());var result=lookup(s,429);
        assertThat(code(result)).isEqualTo("RATE_LIMITED");
        assertThat(Long.parseLong(result.getResponse().getHeader("Retry-After"))).isBetween(1L,60L);
        assertThat(event(result)).containsEntry("target_type","CUSTOMER_LOOKUP_REQUEST").containsEntry("metadata","{\"outcome\": \"RATE_LIMITED\"}");
        assertThat(state(s.identifier())).isEqualTo(before);
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));lookup(s,200);
    }
    @Test void ipBudgetIsSharedAcrossStaffAndIgnoresSpoofedForwardedHeaders() throws Exception {
        var s=lookupSetup();for(int i=0;i<120;i++) {lookupLimiter.reserve(UUID.randomUUID(),s.staff().ip);}
        var result=s.staff().send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(LOOKUP)
                .queryParam("identifier",s.identifier().toString()).queryParam("storeId",s.store().getId().toString())
                .header("X-Forwarded-For","203.0.113.99"),429);
        assertThat(code(result)).isEqualTo("RATE_LIMITED");assertThat(result.getResponse().getHeader("Retry-After")).isNotBlank();event(result);
    }
    @Test void invalidAttemptsConsumeTheStaffBudget() throws Exception {
        var s=lookupSetup();for(int i=0;i<30;i++) {lookup(s.staff(),"invalid-identifier",s.store().getId().toString(),400);}
        lookup(s,429);
    }
}
