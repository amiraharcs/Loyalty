package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import static org.assertj.core.api.Assertions.*;

class GameLifePersistenceTests extends GameLifeTestSupport {
    @Test void sharedOwnerUniquenessAndRestrictiveCustomerReferences() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var owner=customer(browser);
        assertThatThrownBy(()->jdbc.update("INSERT INTO loyalty.game_life_state(customer_id) VALUES (?)",owner)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO loyalty.game_life_state(customer_id) VALUES (?)",UUID.randomUUID())).isInstanceOf(DataAccessException.class);
        // A state-only profile proves this FK rather than unrelated customer history restrictions.
        var stateOnly=UUID.randomUUID();var user=UUID.randomUUID();
        jdbc.update("INSERT INTO loyalty.users(id,canonical_email,password_hash,status,created_at,updated_at) VALUES (?,?,?,'ACTIVE',now(),now())",user,user+"@example.org","synthetic");
        jdbc.update("INSERT INTO loyalty.customer_profiles(id,user_id,display_name,leaderboard_alias,leaderboard_private) VALUES (?,?,'Member','Member',true)",stateOnly,user);
        jdbc.update("INSERT INTO loyalty.game_life_state(customer_id) VALUES (?)",stateOnly);
        assertThatThrownBy(()->jdbc.update("DELETE FROM loyalty.customer_profiles WHERE id=?",stateOnly)).isInstanceOf(DataAccessException.class);
        consume(browser);
        assertThatThrownBy(()->jdbc.update("DELETE FROM loyalty.game_life_state WHERE customer_id=?",owner)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE loyalty.game_life_state SET customer_id=? WHERE customer_id=?",UUID.randomUUID(),owner)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE loyalty.game_life_state SET version=-1 WHERE customer_id=?",owner)).isInstanceOf(DataAccessException.class);
    }
    @Test void consumptionConstraintsAndImmutableHistoryAreEnforcedByPostgres() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var owner=customer(browser);var entry=consume(browser);
        for(String values:java.util.List.of(
                "NULL, '2026-10-14T14:00:00Z'", "'2026-10-14T12:00:00Z', NULL",
                "'2026-10-14T12:00:00Z','2026-10-14T12:00:00Z'",
                "'2026-10-14T12:00:00Z','2026-10-14T11:00:00Z'",
                "'-infinity','2026-10-14T14:00:00Z'", "'2026-10-14T12:00:00Z','infinity'")) {
            assertThatThrownBy(()->jdbc.update("INSERT INTO loyalty.game_life_consumptions(id,customer_id,consumed_at,recharge_at) VALUES (?, ?,"+values+")",UUID.randomUUID(),owner)).isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(()->jdbc.update("INSERT INTO loyalty.game_life_consumptions VALUES (?,?,'2026-10-14T12:00:00Z','2026-10-14T14:00:00Z')",UUID.randomUUID(),UUID.randomUUID())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE loyalty.game_life_consumptions SET recharge_at=recharge_at+interval '1 hour' WHERE id=?",entry.getId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM loyalty.game_life_consumptions WHERE id=?",entry.getId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.execute("TRUNCATE loyalty.game_life_consumptions")).isInstanceOf(DataAccessException.class);
        assertThat(consumed(owner)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema='loyalty' AND table_name='game_life_state' ORDER BY ordinal_position",String.class)).containsExactly("customer_id","version");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema='loyalty' AND table_name='game_sessions'",Long.class)).isZero();
    }
}
