package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class GameScorePersistenceTests extends GameScoreTestSupport {
    private void rejected(Runnable work,String sqlState) {
        assertThatThrownBy(work::run).isInstanceOf(RuntimeException.class).satisfies(error->{
            Throwable cause=error;while(cause.getCause()!=null && !(cause instanceof java.sql.SQLException)) {cause=cause.getCause();}
            assertThat(cause).isInstanceOf(java.sql.SQLException.class);assertThat(((java.sql.SQLException)cause).getSQLState()).isEqualTo(sqlState);
        });
    }
    @Test void completedSessionAndScoreMustCommitTogetherWithIdenticalAcceptanceTime() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var run=start(browser);
        rejected(()->new TransactionTemplate(manager).executeWithoutResult(tx->jdbc.update("UPDATE loyalty.game_sessions SET status='COMPLETED',completed_at=started_at WHERE id=?",run.sessionId())),"23514");
        rejected(()->jdbc.update("INSERT INTO loyalty.game_scores(id,session_id,score,accepted_at) VALUES (?,?,0,?)",UUID.randomUUID(),run.sessionId(),java.sql.Timestamp.from(NOW)),"23514");
        rejected(()->new TransactionTemplate(manager).executeWithoutResult(tx->{
            jdbc.update("UPDATE loyalty.game_sessions SET status='COMPLETED',completed_at=started_at WHERE id=?",run.sessionId());
            jdbc.update("INSERT INTO loyalty.game_scores(id,session_id,score,accepted_at) VALUES (?,?,0,?)",UUID.randomUUID(),run.sessionId(),java.sql.Timestamp.from(NOW.plusSeconds(1)));
        }),"23514");
        assertThat(state(run.sessionId())).isEqualTo("ACTIVE");assertThat(scoreRows(run.sessionId())).isZero();
        submit(browser,run.sessionId(),0,UUID.randomUUID(),201);
    }
    @Test void acceptedScoresHaveUniqueRestrictiveSourcesSafeIntegersAndImmutableEvidence() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var run=start(browser);var accepted=score(submit(browser,run.sessionId(),10,UUID.randomUUID(),201));
        rejected(()->jdbc.update("INSERT INTO loyalty.game_scores(id,session_id,score,accepted_at) VALUES (?,?,1,?)",UUID.randomUUID(),run.sessionId(),java.sql.Timestamp.from(NOW)),"23505");
        rejected(()->jdbc.update("INSERT INTO loyalty.game_scores(id,session_id,score,accepted_at) VALUES (?,?,0,?)",UUID.randomUUID(),UUID.randomUUID(),java.sql.Timestamp.from(NOW)),"23503");
        for(long invalid:new long[]{-1,9007199254740992L}) {
            rejected(()->jdbc.update("INSERT INTO loyalty.game_scores(id,session_id,score,accepted_at) VALUES (?,?,?,?)",UUID.randomUUID(),run.sessionId(),invalid,java.sql.Timestamp.from(NOW)),"23514");
        }
        rejected(()->jdbc.update("UPDATE loyalty.game_scores SET score=1 WHERE id=?",accepted.scoreId()),"23514");
        rejected(()->jdbc.update("DELETE FROM loyalty.game_scores WHERE id=?",accepted.scoreId()),"23514");
        rejected(()->jdbc.execute("TRUNCATE loyalty.game_scores"),"23514");
        rejected(()->jdbc.update("DELETE FROM loyalty.game_sessions WHERE id=?",run.sessionId()),"23514");
        assertThat(scoreRows(run.sessionId())).isEqualTo(1);
    }
    @Test void ordinaryRejectionEventsAreAppendOnlyAndContainOnlySafeOwnedContext() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var run=start(browser);submit(browser,run.sessionId(),101,UUID.randomUUID(),409);
        var event=jdbc.queryForMap("SELECT * FROM loyalty.game_submission_events WHERE actor_user_id=?",browser.userId);
        assertThat(event).containsEntry("session_id",run.sessionId()).containsEntry("submitted_score",101L).containsEntry("reason_code","SCORE_IMPLAUSIBLE");
        assertThat(event.keySet()).containsExactlyInAnyOrder("id","actor_user_id","session_id","submitted_score","reason_code","occurred_at","request_id");
        rejected(()->jdbc.update("UPDATE loyalty.game_submission_events SET submitted_score=0 WHERE id=?",event.get("id")),"23514");
        rejected(()->jdbc.update("DELETE FROM loyalty.game_submission_events WHERE id=?",event.get("id")),"23514");
        rejected(()->jdbc.execute("TRUNCATE loyalty.game_submission_events"),"23514");
        assertThat(scoreRows(run.sessionId())).isZero();
    }
    @Test void unknownEngineFailsClosedWithSafeEventAndNoCompletion() throws Exception {
        var browser=login(RoleCode.CUSTOMER);assume(browser,RoleCode.CUSTOMER);
        var id=new TransactionTemplate(manager).execute(tx->{
            var owner=lives.lockOwnState();var session=new com.amiraharcs.loyalty.game.model.GameSession(owner.customerId(),NOW,NOW.plusSeconds(900),"v2");
            gameEntities.persist(session);gameEntities.flush();lives.consumeForSession(session);return session.getId();
        });
        var result=submit(browser,id,0,UUID.randomUUID(),500);assertThat(code(result)).isEqualTo("INTERNAL_ERROR");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("v2","Unsupported","IllegalState");
        assertThat(state(id)).isEqualTo("ACTIVE");assertThat(scoreRows(id)).isZero();assertThat(rejections(browser.userId,"INTERNAL_ERROR")).isEqualTo(1);
    }
}
