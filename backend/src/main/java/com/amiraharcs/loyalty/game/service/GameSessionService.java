package com.amiraharcs.loyalty.game.service;

import com.amiraharcs.loyalty.audit.service.AuditService;
import com.amiraharcs.loyalty.auth.security.RoleAuthorities;
import com.amiraharcs.loyalty.common.api.*;
import com.amiraharcs.loyalty.common.idempotency.IdempotencyService;
import com.amiraharcs.loyalty.game.api.GameSessionResponse;
import com.amiraharcs.loyalty.game.config.GameSessionRule;
import com.amiraharcs.loyalty.game.model.GameSession;
import com.amiraharcs.loyalty.game.repository.GameSessionRepository;
import jakarta.persistence.EntityManager;
import jakarta.validation.constraints.NotNull;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import tools.jackson.databind.json.JsonMapper;

@Service
@Validated
@PreAuthorize(RoleAuthorities.REQUIRES_CUSTOMER)
@Transactional
public class GameSessionService {
    private static final String OPERATION = "CUSTOMER_GAME_START";
    private static final String PAYLOAD_HASH = IdempotencyService.hash("{}");
    private final GameCustomerService customers;
    private final GameLifeService lives;
    private final GameSessionRepository sessions;
    private final GameSessionRule rule;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final EntityManager entities;
    private final Clock clock;
    private final JsonMapper json;
    public GameSessionService(GameCustomerService customers, GameLifeService lives, GameSessionRepository sessions,
            GameSessionRule rule, IdempotencyService idempotency, AuditService audit, EntityManager entities,
            Clock clock, JsonMapper json) {
        this.customers = customers; this.lives = lives; this.sessions = sessions; this.rule = rule;
        this.idempotency = idempotency; this.audit = audit; this.entities = entities; this.clock = clock; this.json = json;
    }
    public GameSessionResponse start(@NotNull UUID key, @NotNull UUID requestId) {
        var customer = customers.current();
        // Infrastructure retry lock precedes business aggregates, as in other mutation workflows.
        var previous = idempotency.previous(customer.actorId(), OPERATION, key, PAYLOAD_HASH);
        lives.lockOwnState();
        customers.current();
        if (previous.isPresent()) { return json.readValue(previous.get(), GameSessionResponse.class); }
        var active = sessions.lockActive(customer.customerId());
        customers.current();
        Instant now = serverNow();
        if (active.isPresent()) {
            var session = active.get();
            if (!session.expireIfStale(now)) { throw new GameSessionActiveException(); }
            expiredAudit(customer, session, now, requestId);
            entities.flush(); // Free the partial unique ACTIVE slot before inserting the replacement.
        }
        if (lives.snapshotAt(customer.customerId(), now).availableLives() == 0) { throw new NoGameLivesException(); }
        var session = new GameSession(customer.customerId(), now, now.plus(rule.lifetime()), rule.engineVersion());
        entities.persist(session);
        entities.flush();
        lives.consumeForSession(session); // MANDATORY joins this transaction; no independent commit.
        var response = GameSessionResponse.from(session, lives.snapshotAt(customer.customerId(), now));
        audit.append(customer.actorId(), "GAME_SESSION_STARTED", "GAME_SESSION", session.getId(), now, requestId,
                json.writeValueAsString(Map.of("customerId", customer.customerId(), "engineVersion", session.getEngineVersion())));
        idempotency.complete(customer.actorId(), OPERATION, key, PAYLOAD_HASH, json.writeValueAsString(response), now);
        return response;
    }
    public GameSessionResponse owned(@NotNull UUID id, @NotNull UUID requestId) {
        var customer = lives.lockOwnState();
        var session = sessions.lockOwned(id, customer.customerId()).orElseThrow(ResourceNotFoundException::new);
        customers.current();
        Instant now = serverNow();
        if (session.expireIfStale(now)) {
            expiredAudit(customer, session, now, requestId);
            entities.flush();
        }
        return GameSessionResponse.from(session, lives.snapshotAt(customer.customerId(), now));
    }
    private void expiredAudit(GameCustomerService.Customer customer, GameSession session, Instant now, UUID requestId) {
        audit.append(customer.actorId(), "GAME_SESSION_EXPIRED", "GAME_SESSION", session.getId(), now, requestId, "{}");
    }
    private Instant serverNow() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}
