package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class GameSessionPersistenceTests extends GameSessionTestSupport {
    private void rejected(Runnable action, String state) {
        assertThatThrownBy(action::run).isInstanceOf(RuntimeException.class).satisfies(error->{
            Throwable cause=error;
            while(cause.getCause()!=null && !(cause instanceof java.sql.SQLException)) {cause=cause.getCause();}
            assertThat(cause).isInstanceOf(java.sql.SQLException.class);
            assertThat(((java.sql.SQLException)cause).getSQLState()).isEqualTo(state);
        });
    }
    @Test void everyNewSessionMustCommitWithExactlyOneMatchingConsumption() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var owner=customer(browser);var id=UUID.randomUUID();
        rejected(()->new TransactionTemplate(manager).executeWithoutResult(tx->jdbc.update(
                "INSERT INTO loyalty.game_sessions(id,customer_id,started_at,expires_at,status,engine_version) VALUES (?,?,'2026-10-14T12:00:00Z','2026-10-14T12:15:00Z','ACTIVE','v1')",id,owner)),"23514");
        assertThat(sessionRows(owner)).isZero();
        rejected(()->jdbc.update("INSERT INTO loyalty.game_life_consumptions(id,customer_id,consumed_at,recharge_at) VALUES (?,?,'2026-10-14T12:00:00Z','2026-10-14T14:00:00Z')",UUID.randomUUID(),owner),"23514");
        var session=start(browser);
        rejected(()->jdbc.update("INSERT INTO loyalty.game_life_consumptions(id,customer_id,consumed_at,recharge_at,session_id) VALUES (?,?,'2026-10-14T12:00:00Z','2026-10-14T14:00:00Z',?)",UUID.randomUUID(),owner,session.sessionId()),"23505");
        assertThat(consumed(owner)).isEqualTo(1);
    }
    @Test void uniqueActiveSlotAndRestrictiveSessionSourceReferencesAreDatabaseEnforced() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var owner=customer(browser);var session=start(browser);
        rejected(()->jdbc.update("INSERT INTO loyalty.game_sessions(id,customer_id,started_at,expires_at,status,engine_version) VALUES (?,?,'2026-10-14T12:00:00Z','2026-10-14T12:15:00Z','ACTIVE','v1')",UUID.randomUUID(),owner),"23505");
        rejected(()->jdbc.update("INSERT INTO loyalty.game_sessions(id,customer_id,started_at,expires_at,status,engine_version) VALUES (?,?,'2026-10-14T12:00:00Z','2026-10-14T12:15:00Z','ACTIVE','v1')",UUID.randomUUID(),UUID.randomUUID()),"23503");
        rejected(()->jdbc.update("DELETE FROM loyalty.game_sessions WHERE id=?",session.sessionId()),"23514");
        rejected(()->jdbc.execute("TRUNCATE loyalty.game_sessions CASCADE"),"23514");
        rejected(()->jdbc.update("DELETE FROM loyalty.customer_profiles WHERE id=?",owner),"23503");
    }
    @Test void consumptionLinkRequiresCorrectSessionOwnerAndExactServerStartInstant() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var owner=customer(browser);var session=start(browser);
        var other=login(RoleCode.CUSTOMER);var otherOwner=customer(other);
        for(String mode:java.util.List.of("foreign-owner","wrong-time","missing-session")) {
            var id=UUID.randomUUID();
            rejected(()->new TransactionTemplate(manager).executeWithoutResult(tx->{
                jdbc.update("INSERT INTO loyalty.game_sessions(id,customer_id,started_at,expires_at,status,engine_version) VALUES (?,?,'2026-10-14T12:00:00Z','2026-10-14T12:15:00Z','EXPIRED','v1')",id,owner);
                jdbc.update("INSERT INTO loyalty.game_life_consumptions(id,customer_id,consumed_at,recharge_at,session_id) VALUES (?,?,?,?,?)",
                        UUID.randomUUID(),mode.equals("foreign-owner")?otherOwner:owner,
                        java.sql.Timestamp.from(mode.equals("wrong-time")?NOW.plusSeconds(1):NOW),java.sql.Timestamp.from(NOW.plusSeconds(7200)),
                        mode.equals("missing-session")?UUID.randomUUID():id);
            }),"23503");
        }
        assertThat(consumed(owner)).isEqualTo(1);assertThat(consumed(otherOwner)).isZero();
    }
    @ParameterizedTest @ValueSource(strings={
            "'2026-10-14T12:00:00Z','2026-10-14T12:00:00Z',NULL,'EXPIRED','v1'",
            "'2026-10-14T12:00:00Z','2026-10-14T11:00:00Z',NULL,'EXPIRED','v1'",
            "'2026-10-14T12:00:00Z','infinity',NULL,'EXPIRED','v1'",
            "'-infinity','2026-10-14T12:15:00Z',NULL,'EXPIRED','v1'",
            "'2026-10-14T12:00:00Z','2026-10-14T12:15:00Z',NULL,'INVALID','v1'",
            "'2026-10-14T12:00:00Z','2026-10-14T12:15:00Z',NULL,'COMPLETED','v1'",
            "'2026-10-14T12:00:00Z','2026-10-14T12:15:00Z','2026-10-14T12:01:00Z','ACTIVE','v1'",
            "'2026-10-14T12:00:00Z','2026-10-14T12:15:00Z','2026-10-14T12:15:00Z','COMPLETED','v1'",
            "'2026-10-14T12:00:00Z','2026-10-14T12:15:00Z','2026-10-14T11:59:59Z','COMPLETED','v1'",
            "'2026-10-14T12:00:00Z','2026-10-14T12:15:00Z',NULL,'EXPIRED',' '",
            "'2026-10-14T12:00:00Z','2026-10-14T12:15:00Z',NULL,'EXPIRED','bad version'"})
    void rejectsInvalidSessionTimesStatusesAndCompletionCombinations(String values) throws Exception {
        var browser=login(RoleCode.CUSTOMER);var owner=customer(browser);
        rejected(()->jdbc.update("INSERT INTO loyalty.game_sessions(id,customer_id,started_at,expires_at,completed_at,status,engine_version) VALUES (?, ?,"+values+")",UUID.randomUUID(),owner),"23514");
        noStart(browser);
    }
    @Test void sourceSnapshotsAndTerminalStateCannotBeRewritten() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var session=start(browser);
        for(String assignment:java.util.List.of("engine_version='v2'","started_at=started_at+interval '1 second'","expires_at=expires_at+interval '1 second'","customer_id='"+UUID.randomUUID()+"'")) {
            rejected(()->jdbc.update("UPDATE loyalty.game_sessions SET "+assignment+" WHERE id=?",session.sessionId()),"23514");
        }
        org.mockito.Mockito.when(clock.instant()).thenReturn(session.expiresAt());recovery(browser,session.sessionId());
        rejected(()->jdbc.update("UPDATE loyalty.game_sessions SET status='ACTIVE' WHERE id=?",session.sessionId()),"23514");
        rejected(()->jdbc.update("UPDATE loyalty.game_sessions SET status='COMPLETED',completed_at=started_at+interval '1 minute' WHERE id=?",session.sessionId()),"23514");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema='loyalty' AND table_name='weekly_periods'",Long.class)).isZero();
    }
}
