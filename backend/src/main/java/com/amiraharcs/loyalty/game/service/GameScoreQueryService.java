package com.amiraharcs.loyalty.game.service;

import com.amiraharcs.loyalty.auth.security.RoleAuthorities;
import com.amiraharcs.loyalty.common.api.ResourceNotFoundException;
import com.amiraharcs.loyalty.game.api.*;
import com.amiraharcs.loyalty.game.repository.*;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize(RoleAuthorities.REQUIRES_CUSTOMER)
@Transactional(readOnly=true)
public class GameScoreQueryService {
    private final GameCustomerService customers;
    private final GameScoreRepository scores;
    private final GameSessionRepository sessions;
    public GameScoreQueryService(GameCustomerService customers,GameScoreRepository scores,GameSessionRepository sessions) {
        this.customers=customers;this.scores=scores;this.sessions=sessions;
    }
    public GameSummaryResponse summary() {return new GameSummaryResponse(scores.personalBest(customers.current().customerId()));}
    public ScoreResponse owned(UUID sessionId) {
        var owner=customers.current();
        if(!sessions.existsByIdAndCustomerId(sessionId,owner.customerId())) {throw new ResourceNotFoundException();}
        return scores.findBySessionId(sessionId).map(ScoreResponse::from).orElseThrow(ResourceNotFoundException::new);
    }
}
