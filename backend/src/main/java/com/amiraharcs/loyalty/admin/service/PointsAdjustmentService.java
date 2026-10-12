package com.amiraharcs.loyalty.admin.service;

import com.amiraharcs.loyalty.admin.api.*;
import com.amiraharcs.loyalty.audit.service.AuditService;
import com.amiraharcs.loyalty.auth.model.*;
import com.amiraharcs.loyalty.auth.security.*;
import com.amiraharcs.loyalty.auth.service.AuthenticationIdentityService;
import com.amiraharcs.loyalty.common.api.*;
import com.amiraharcs.loyalty.common.idempotency.IdempotencyService;
import com.amiraharcs.loyalty.loyalty.model.*;
import com.amiraharcs.loyalty.loyalty.repository.PointTransactionRepository;
import com.amiraharcs.loyalty.loyalty.service.LoyaltyAccountService;
import jakarta.persistence.EntityManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import tools.jackson.databind.json.JsonMapper;

@Service
@Validated
public class PointsAdjustmentService {
    private static final String OPERATION = "ADMIN_POINTS_ADJUSTMENT";
    private static final Logger LOG = LoggerFactory.getLogger(PointsAdjustmentService.class);
    private final LoyaltyAccountService accounts;
    private final PointTransactionRepository ledger;
    private final AuthenticationIdentityService identities;
    private final EntityManager entities;
    private final Clock clock;
    private final AuditService audit;
    private final IdempotencyService idempotency;
    private final JsonMapper json;
    public PointsAdjustmentService(LoyaltyAccountService accounts, PointTransactionRepository ledger,
            AuthenticationIdentityService identities, EntityManager entities, Clock clock, AuditService audit,
            IdempotencyService idempotency, JsonMapper json) {
        this.accounts = accounts; this.ledger = ledger; this.identities = identities; this.entities = entities;
        this.clock = clock; this.audit = audit; this.idempotency = idempotency; this.json = json;
    }
    @PreAuthorize(RoleAuthorities.REQUIRES_ADMIN)
    @Transactional
    public PointsAdjustmentResponse adjust(@NotNull UUID customerId, @NotNull @Valid PointsAdjustmentRequest request,
            @NotNull UUID key, @NotNull UUID requestId) {
        UUID actorId = currentAdmin();
        String payloadHash = IdempotencyService.hash(json.writeValueAsString(java.util.List.of(customerId, request.signedAmount(), request.reason())));
        var previous = idempotency.previous(actorId, OPERATION, key, payloadHash);
        var account = accounts.lockForMutation(customerId);
        // Eligibility is checked after waiting for the account lock, including on retries.
        currentAdmin();
        var customer = identities.current(account.getCustomer().getUser().getId()).orElseThrow(ResourceNotFoundException::new);
        if (!customer.response().roles().contains(RoleCode.CUSTOMER)) { throw new ResourceNotFoundException(); }
        if (previous.isPresent()) { return json.readValue(previous.get(), PointsAdjustmentResponse.class); }
        long amount = request.signedAmount().longValueExact();
        long newBalance;
        try { newBalance = PointValues.checkedTotal(accounts.currentBalance(customerId), amount); }
        catch (IllegalStateException unsupportedTotal) { throw new ConflictException(); }
        if (newBalance < 0) {
            // This security event remains in the application log even when the transaction rolls back.
            LOG.warn("Admin adjustment rejected action={} actorId={} targetId={} requestId={} outcome=INSUFFICIENT_POINTS",
                    OPERATION, actorId, customerId, requestId);
            throw new InsufficientPointsException();
        }
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var entry = ledger.append(new PointTransaction(account, amount, PointTransactionType.ADJUSTMENT,
                entities.getReference(User.class, actorId), request.reason(), now));
        var response = new PointsAdjustmentResponse(entry.getId(), amount, request.reason(), now, newBalance);
        audit.append(actorId, OPERATION, "CUSTOMER", customerId, now, requestId,
                json.writeValueAsString(Map.of("transactionId", entry.getId(), "signedAmount", amount)));
        idempotency.complete(actorId, OPERATION, key, payloadHash, json.writeValueAsString(response), now);
        return response;
    }
    private UUID currentAdmin() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof SessionPrincipal principal)) {
            throw new BadCredentialsException("Authentication failed");
        }
        var grants = identities.activeRoleGrants(principal.userId())
                .orElseThrow(() -> new BadCredentialsException("Authentication failed"));
        if (!grants.contains(RoleCode.ADMIN)) { throw new AccessDeniedException("Access denied"); }
        return principal.userId();
    }
}
