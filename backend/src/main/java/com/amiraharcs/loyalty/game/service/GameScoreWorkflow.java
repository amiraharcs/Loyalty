package com.amiraharcs.loyalty.game.service;

import com.amiraharcs.loyalty.audit.service.AuditService;
import com.amiraharcs.loyalty.common.api.*;
import com.amiraharcs.loyalty.common.idempotency.IdempotencyService;
import com.amiraharcs.loyalty.game.api.*;
import com.amiraharcs.loyalty.game.model.*;
import com.amiraharcs.loyalty.game.repository.*;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@Service
@Transactional(propagation=Propagation.MANDATORY)
public class GameScoreWorkflow {
    private static final String OPERATION="CUSTOMER_GAME_SCORE";
    private final GameCustomerService customers;
    private final GameLifeService lives;
    private final GameSessionRepository sessions;
    private final GameScoreRepository scores;
    private final IdempotencyService idempotency;
    private final ScorePlausibility plausibility;
    private final AuditService audit;
    private final EntityManager entities;
    private final Clock clock;
    private final JsonMapper json;
    public GameScoreWorkflow(GameCustomerService customers,GameLifeService lives,GameSessionRepository sessions,GameScoreRepository scores,
            IdempotencyService idempotency,ScorePlausibility plausibility,AuditService audit,EntityManager entities,Clock clock,JsonMapper json) {
        this.customers=customers;this.lives=lives;this.sessions=sessions;this.scores=scores;this.idempotency=idempotency;
        this.plausibility=plausibility;this.audit=audit;this.entities=entities;this.clock=clock;this.json=json;
    }
    public Outcome submit(UUID sessionId,long value,UUID key,UUID requestId) {
        var customer=customers.current();
        String hash=IdempotencyService.hash(json.writeValueAsString(List.of(sessionId,value)));
        var previous=idempotency.previous(customer.actorId(),OPERATION,key,hash);
        lives.lockOwnState();
        var session=sessions.lockOwned(sessionId,customer.customerId()).orElseThrow(ResourceNotFoundException::new);
        customers.current();
        if(previous.isPresent()) {return new Outcome(json.readValue(previous.get(),ScoreResult.class),null);}
        var accepted=scores.findBySessionId(sessionId);
        if(accepted.isPresent()) {
            if(accepted.get().getScore()!=value) {throw new GameScoreConflictException(ApiErrorCode.SCORE_ALREADY_SUBMITTED);}
            return success(customer.actorId(),key,hash,new ScoreResult(200,ScoreResponse.from(accepted.get())),clock.instant());
        }
        var now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        if(session.expireIfStale(now)) {
            audit.append(customer.actorId(),"GAME_SESSION_EXPIRED","GAME_SESSION",sessionId,now,requestId,"{}");
            entities.flush();
        }
        if(session.getStatus()==GameSessionStatus.EXPIRED) {return new Outcome(null,ApiErrorCode.SESSION_EXPIRED);}
        if(session.getStatus()!=GameSessionStatus.ACTIVE) {throw new IllegalStateException("Completed session has no score");}
        if(value>plausibility.ceiling(session,now)) {throw new GameScoreConflictException(ApiErrorCode.SCORE_IMPLAUSIBLE);}
        var score=new GameScore(sessionId,value,now);entities.persist(score);
        session.complete(now);entities.flush();
        audit.append(customer.actorId(),"GAME_SCORE_ACCEPTED","GAME_SCORE",score.getId(),now,requestId,
                json.writeValueAsString(Map.of("sessionId",sessionId,"score",value)));
        return success(customer.actorId(),key,hash,new ScoreResult(201,ScoreResponse.from(score)),now);
    }
    private Outcome success(UUID actor,UUID key,String hash,ScoreResult result,Instant now) {
        idempotency.complete(actor,OPERATION,key,hash,json.writeValueAsString(result),now);
        return new Outcome(result,null);
    }
    /** Expiry rejection returns normally so its safe lazy transition commits before the HTTP conflict. */
    public record Outcome(ScoreResult result,ApiErrorCode rejection) { }
}
