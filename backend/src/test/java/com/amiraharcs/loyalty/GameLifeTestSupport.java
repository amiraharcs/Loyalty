package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.game.api.GameLivesResponse;
import com.amiraharcs.loyalty.game.model.GameLifeConsumption;
import com.amiraharcs.loyalty.game.service.GameLifeService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import static org.mockito.Mockito.when;

abstract class GameLifeTestSupport extends StaffQrTestSupport {
    static final String LIVES = "/api/v1/customers/me/game-lives";
    @Autowired GameLifeService lives;
    @Autowired jakarta.persistence.EntityManager gameEntities;
    GameLifeConsumption consume(Browser browser) {
        assume(browser, RoleCode.CUSTOMER);
        return new TransactionTemplate(manager).execute(tx -> {
            // Isolate life calculation with real linked, terminal session fixtures.
            var customer = lives.lockOwnState();
            var now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            var session = new com.amiraharcs.loyalty.game.model.GameSession(customer.customerId(), now, now.plusSeconds(900), "v1");
            gameEntities.persist(session); gameEntities.flush();
            var consumption = lives.consumeForSession(session);
            session.expireIfStale(session.getExpiresAt()); gameEntities.flush();
            return consumption;
        });
    }
    GameLivesResponse snapshot(Browser browser, Instant now) {
        when(clock.instant()).thenReturn(now);
        assume(browser, RoleCode.CUSTOMER);
        return lives.current();
    }
    long consumed(UUID owner) {
        return jdbc.queryForObject("SELECT count(*) FROM loyalty.game_life_consumptions WHERE customer_id=?", Long.class, owner);
    }
    long version(UUID owner) {
        return jdbc.queryForObject("SELECT version FROM loyalty.game_life_state WHERE customer_id=?", Long.class, owner);
    }
}
