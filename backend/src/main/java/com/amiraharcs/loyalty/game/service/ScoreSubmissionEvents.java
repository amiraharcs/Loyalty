package com.amiraharcs.loyalty.game.service;

import com.amiraharcs.loyalty.common.api.ApiErrorCode;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Dedicated ordinary-gameplay rejection evidence; no credentials, bodies or telemetry. */
@Service
public class ScoreSubmissionEvents {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public ScoreSubmissionEvents(JdbcTemplate jdbc,Clock clock) {this.jdbc=jdbc;this.clock=clock;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void rejected(UUID actor,UUID attemptedSession,long score,ApiErrorCode reason,UUID requestId) {
        jdbc.update("INSERT INTO loyalty.game_submission_events(id,actor_user_id,session_id,submitted_score,reason_code,occurred_at,request_id) "
                +"VALUES (?, ?, (SELECT s.id FROM loyalty.game_sessions s JOIN loyalty.customer_profiles c ON c.id=s.customer_id WHERE s.id=? AND c.user_id=?), ?, ?, ?, ?)",
                UUID.randomUUID(),actor,attemptedSession,actor,score,reason.name(),java.sql.Timestamp.from(clock.instant()),requestId);
    }
}
