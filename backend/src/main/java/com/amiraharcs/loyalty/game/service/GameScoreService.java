package com.amiraharcs.loyalty.game.service;

import com.amiraharcs.loyalty.auth.security.RoleAuthorities;
import com.amiraharcs.loyalty.common.api.*;
import com.amiraharcs.loyalty.game.api.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.slf4j.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
@PreAuthorize(RoleAuthorities.REQUIRES_CUSTOMER)
@Transactional(propagation=Propagation.NEVER)
public class GameScoreService {
    private static final Logger LOG=LoggerFactory.getLogger(GameScoreService.class);
    private final GameCustomerService customers;
    private final GameScoreWorkflow workflow;
    private final ScoreRateLimiter limiter;
    private final ScoreSubmissionEvents events;
    private final TransactionTemplate transactions;
    public GameScoreService(GameCustomerService customers,GameScoreWorkflow workflow,ScoreRateLimiter limiter,
            ScoreSubmissionEvents events,PlatformTransactionManager manager) {
        this.customers=customers;this.workflow=workflow;this.limiter=limiter;this.events=events;transactions=new TransactionTemplate(manager);
    }
    public ScoreResult submit(@NotNull UUID sessionId,@NotNull @Valid ScoreRequest request,@NotNull UUID key,
            @NotNull UUID requestId,@NotBlank String address) {
        var customer=customers.current();
        try {
            limiter.reserve(customer.actorId(),address);
            var outcome=transactions.execute(tx->workflow.submit(sessionId,request.score().longValueExact(),key,requestId));
            if(outcome.rejection()!=null) {throw new GameScoreConflictException(outcome.rejection());}
            return outcome.result();
        } catch(RuntimeException failure) {
            ApiErrorCode code=failure instanceof ConflictException conflict?conflict.code():
                    failure instanceof ResourceNotFoundException?ApiErrorCode.RESOURCE_NOT_FOUND:
                    failure instanceof RateLimitException?ApiErrorCode.RATE_LIMITED:
                    failure instanceof AuthenticationException?ApiErrorCode.UNAUTHENTICATED:
                    failure instanceof AccessDeniedException?ApiErrorCode.FORBIDDEN:ApiErrorCode.INTERNAL_ERROR;
            try {events.rejected(customer.actorId(),sessionId,request.score().longValueExact(),code,requestId);}
            catch(RuntimeException unavailable) {LOG.error("Game rejection event unavailable actorId={} requestId={} code={}",customer.actorId(),requestId,code);}
            throw failure;
        }
    }
}
