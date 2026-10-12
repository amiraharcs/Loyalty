package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "loyalty.visits.points-per-visit=250")
class ConfiguredVisitAwardTests extends VisitTestSupport {
    @Test
    void configuredAwardIsRespectedAndCannotRewritePreviousSnapshot() {
        var setup = new TransactionTemplate(manager).execute(status -> fixture());
        new TransactionTemplate(manager).executeWithoutResult(status -> append(setup, NOW.minusSeconds(86400)));
        authenticate(setup.staff(), RoleCode.STAFF);
        var result = new TransactionTemplate(manager).execute(status -> service.confirmResolvedCustomer(setup.customer().getId(), setup.store().getId()));
        assertThat(result.earnedPoints()).isEqualTo(250); assertThat(result.balance()).isEqualTo(350);
        assertThat(service.history(setup.customer().getId(), 0, 10)).extracting(item -> item.earnedPoints()).containsExactly(250L, 100L);
        assertThat(loyalty.history(setup.customer().getId(), 0, 10)).extracting(item -> item.signedAmount()).containsExactly(250L, 100L);
    }
}
