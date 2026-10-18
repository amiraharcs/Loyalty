package com.amiraharcs.loyalty.game.service;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.auth.security.*;
import com.amiraharcs.loyalty.auth.service.AuthenticationIdentityService;
import com.amiraharcs.loyalty.common.api.*;
import com.amiraharcs.loyalty.customer.repository.CustomerProfileRepository;
import com.amiraharcs.loyalty.game.api.GameLivesResponse;
import com.amiraharcs.loyalty.game.config.GameLifeRule;
import com.amiraharcs.loyalty.game.model.*;
import com.amiraharcs.loyalty.game.repository.*;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly = true)
public class GameLifeService {
    private final GameLifeStateRepository states;
    private final GameLifeConsumptionRepository consumptions;
    private final AuthenticationIdentityService identities;
    private final CustomerProfileRepository profiles;
    private final EntityManager entities;
    private final GameLifeRule rule;
    private final Clock clock;

    public GameLifeService(GameLifeStateRepository states, GameLifeConsumptionRepository consumptions,
            AuthenticationIdentityService identities, CustomerProfileRepository profiles,
            EntityManager entities, GameLifeRule rule, Clock clock) {
        this.states = states;
        this.consumptions = consumptions;
        this.identities = identities;
        this.profiles = profiles;
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
        UUID customerId = ownCustomer();
        states.findById(customerId).orElseThrow(GameLifeService::missingState);
        return snapshot(customerId, serverNow());
    }

    /** Internal command only. The future session workflow must own this entire transaction. */
    @PreAuthorize(RoleAuthorities.REQUIRES_CUSTOMER)
    @Transactional(propagation = Propagation.MANDATORY)
    public GameLifeConsumption consumeOwnLife() {
        UUID customerId = ownCustomer();
        states.lockForConsumption(customerId).orElseThrow(GameLifeService::missingState);
        ownCustomer(); // Recheck active status and explicit role after a potentially blocking lock.
        Instant now = serverNow();
        if (snapshot(customerId, now).availableLives() == 0) { throw new NoGameLivesException(); }
        var consumption = new GameLifeConsumption(customerId, now, now.plus(rule.rechargeInterval()));
        entities.persist(consumption);
        entities.flush(); // Database failures must abort the caller's complete transaction.
        return consumption;
    }

    private GameLivesResponse snapshot(UUID customerId, Instant now) {
        var active = consumptions.activeAt(customerId, now);
        if (active.getUsed() < 0 || active.getUsed() > rule.maxLives()) {
            throw new IllegalStateException("Game life consumption invariant violated");
        }
        return new GameLivesResponse(rule.maxLives() - Math.toIntExact(active.getUsed()),
                rule.maxLives(), active.getNextRechargeAt(), now);
    }

    private UUID ownCustomer() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof SessionPrincipal principal)) {
            throw new BadCredentialsException("Authentication failed");
        }
        var grants = identities.activeRoleGrants(principal.userId())
                .orElseThrow(() -> new BadCredentialsException("Authentication failed"));
        if (!grants.contains(RoleCode.CUSTOMER)) { throw new AccessDeniedException("Access denied"); }
        return profiles.findByUserId(principal.userId()).orElseThrow(ResourceNotFoundException::new).getId();
    }

    private Instant serverNow() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private static IllegalStateException missingState() {
        return new IllegalStateException("Required customer game life state is missing");
    }
}
