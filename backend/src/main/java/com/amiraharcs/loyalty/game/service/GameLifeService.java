package com.amiraharcs.loyalty.game.service;

import com.amiraharcs.loyalty.auth.security.*;
import com.amiraharcs.loyalty.common.api.*;
import com.amiraharcs.loyalty.game.api.GameLivesResponse;
import com.amiraharcs.loyalty.game.config.GameLifeRule;
import com.amiraharcs.loyalty.game.model.*;
import com.amiraharcs.loyalty.game.repository.*;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly = true)
public class GameLifeService {
    private final GameLifeStateRepository states;
    private final GameLifeConsumptionRepository consumptions;
    private final GameCustomerService customers;
    private final EntityManager entities;
    private final GameLifeRule rule;
    private final Clock clock;

    public GameLifeService(GameLifeStateRepository states, GameLifeConsumptionRepository consumptions,
            GameCustomerService customers,
            EntityManager entities, GameLifeRule rule, Clock clock) {
        this.states = states;
        this.consumptions = consumptions;
        this.customers = customers;
        this.entities = entities;
        this.rule = rule;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void createForRegistration(UUID customerId) {
        entities.flush(); // Establish the newly registered profile's shared scalar FK first.
        entities.persist(new GameLifeState(customerId));
    }

    @PreAuthorize(RoleAuthorities.REQUIRES_CUSTOMER)
    public GameLivesResponse current() {
        UUID customerId = customers.current().customerId();
        states.findById(customerId).orElseThrow(GameLifeService::missingState);
        return snapshotAt(customerId, serverNow());
    }

    /** The aggregate is always the first business lock, before any session lookup/lock. */
    @PreAuthorize(RoleAuthorities.REQUIRES_CUSTOMER)
    @Transactional(propagation = Propagation.MANDATORY)
    public GameCustomerService.Customer lockOwnState() {
        var customer = customers.current();
        states.lockForUpdate(customer.customerId()).orElseThrow(GameLifeService::missingState);
        return customers.current();
    }

    /** Internal session collaboration; a real persisted session and caller transaction are required. */
    @PreAuthorize(RoleAuthorities.REQUIRES_CUSTOMER)
    @Transactional(propagation = Propagation.MANDATORY)
    public GameLifeConsumption consumeForSession(GameSession session) {
        UUID customerId = customers.current().customerId();
        if (session == null || !entities.contains(session) || !session.getCustomerId().equals(customerId)
                || session.getStatus() != GameSessionStatus.ACTIVE) {
            throw new IllegalArgumentException("Consumption requires an owned managed active session");
        }
        // The session workflow already holds this aggregate lock before creating/locking its session.
        var state = states.findById(customerId).orElseThrow(GameLifeService::missingState);
        var held = entities.getLockMode(state);
        if (held != jakarta.persistence.LockModeType.PESSIMISTIC_WRITE
                && held != jakarta.persistence.LockModeType.PESSIMISTIC_FORCE_INCREMENT) {
            throw new IllegalStateException("Game state must be locked before creating a session");
        }
        entities.lock(state, jakarta.persistence.LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        customers.current();
        Instant now = session.getStartedAt(); // The server start instant is also the immutable consumption instant.
        if (snapshotAt(customerId, now).availableLives() == 0) { throw new NoGameLivesException(); }
        var consumption = new GameLifeConsumption(customerId, session.getId(), now, now.plus(rule.rechargeInterval()));
        entities.persist(consumption);
        entities.flush();
        return consumption;
    }

    GameLivesResponse snapshotAt(UUID customerId, Instant now) {
        var active = consumptions.activeAt(customerId, now);
        if (active.getUsed() < 0 || active.getUsed() > rule.maxLives()) {
            throw new IllegalStateException("Game life consumption invariant violated");
        }
        return new GameLivesResponse(rule.maxLives() - Math.toIntExact(active.getUsed()),
                rule.maxLives(), active.getNextRechargeAt(), now);
    }

    private Instant serverNow() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private static IllegalStateException missingState() {
        return new IllegalStateException("Required customer game life state is missing");
    }
}
