package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.api.RegistrationRequest;
import com.amiraharcs.loyalty.game.service.GameLifeService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GameLifeRegistrationTests extends StaffQrTestSupport {
    @MockitoSpyBean GameLifeService lives;
    @org.springframework.beans.factory.annotation.Autowired jakarta.persistence.EntityManager entities;
    @Test void failureAfterGameStateFlushRollsBackAllRegistrationRows() {
        String counts="SELECT (SELECT count(*) FROM loyalty.users) AS users,(SELECT count(*) FROM loyalty.user_roles) AS grants,"
                +"(SELECT count(*) FROM loyalty.customer_profiles) AS profiles,(SELECT count(*) FROM loyalty.loyalty_accounts) AS accounts,"
                +"(SELECT count(*) FROM loyalty.streaks) AS streaks,(SELECT count(*) FROM loyalty.game_life_state) AS game_states";
        var before=jdbc.queryForMap(counts);var email=UUID.randomUUID()+"@example.org";
        doAnswer(call->{call.callRealMethod();entities.flush();throw new IllegalStateException("Synthetic game state failure");})
                .when(AopTestUtils.<GameLifeService>getUltimateTargetObject(lives)).createForRegistration(any());
        try {assertThatThrownBy(()->registration.register(new RegistrationRequest(email,PASSWORD,"Member"))).isInstanceOf(IllegalStateException.class);}
        finally {reset(lives);}
        assertThat(jdbc.queryForMap(counts)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.users WHERE canonical_email=?",Long.class,email)).isZero();
    }
}
