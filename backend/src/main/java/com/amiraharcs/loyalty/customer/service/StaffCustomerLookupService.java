package com.amiraharcs.loyalty.customer.service;

import com.amiraharcs.loyalty.audit.service.CustomerLookupAudit;
import com.amiraharcs.loyalty.audit.service.CustomerLookupAudit.Outcome;
import com.amiraharcs.loyalty.auth.security.RoleAuthorities;
import com.amiraharcs.loyalty.common.api.*;
import com.amiraharcs.loyalty.customer.api.StaffCustomerLookupResponse;
import com.amiraharcs.loyalty.store.service.StaffStoreScopeService;
import java.time.Clock;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Read-only service discovery. It never issues a trusted visit resolution or mutates domain state. */
@Service
@PreAuthorize(RoleAuthorities.REQUIRES_STAFF)
@Transactional(propagation = Propagation.NEVER)
public class StaffCustomerLookupService {
    private static final String UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private final StaffStoreScopeService scope;
    private final CustomerServiceLookupQuery query;
    private final CustomerLookupRateLimiter limiter;
    private final CustomerLookupAudit audit;
    private final Clock clock;
    private final TransactionTemplate reads;
    public StaffCustomerLookupService(StaffStoreScopeService scope, CustomerServiceLookupQuery query,
            CustomerLookupRateLimiter limiter, CustomerLookupAudit audit, Clock clock, PlatformTransactionManager manager) {
        this.scope = scope; this.query = query; this.limiter = limiter; this.audit = audit; this.clock = clock;
        reads = new TransactionTemplate(manager);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public StaffCustomerLookupResponse lookup(String identifier, String store, UUID requestId, String address, boolean validQuery) {
        UUID actor = scope.currentStaffId();
        UUID trustedStore = null;
        UUID customer = null;
        Outcome outcome = Outcome.FAILED;
        StaffCustomerLookupResponse result;
        try {
            limiter.reserve(actor, address);
            if (!validQuery) { throw new InvalidRequestException(); }
            UUID storeId = parse(store);
            outcome = Outcome.STORE_DENIED;
            trustedStore = scope.requireAssignedStore(storeId).id();
            UUID customerId = parse(identifier);
            outcome = Outcome.NOT_FOUND;
            result = reads.execute(status -> {
                // Recheck fresh scope inside the same snapshot as the narrow customer projection.
                scope.requireAssignedStore(storeId);
                return query.find(customerId, storeId, clock.instant());
            });
            customer = customerId;
        } catch (RuntimeException failure) {
            if (failure instanceof RateLimitException) { outcome = Outcome.RATE_LIMITED; }
            else if (failure instanceof InvalidRequestException) { outcome = Outcome.INVALID_REQUEST; }
            else if (!(failure instanceof ResourceNotFoundException)) { outcome = Outcome.FAILED; }
            audit.record(actor, trustedStore, null, requestId, outcome);
            throw failure;
        }
        // Fail closed: never disclose a successful result unless its independent audit commit succeeds.
        audit.record(actor, trustedStore, customer, requestId, Outcome.SUCCESS);
        return result;
    }
    private static UUID parse(String value) {
        if (value == null || value.length() != 36 || !value.matches(UUID_PATTERN)) { throw new InvalidRequestException(); }
        return UUID.fromString(value);
    }
}
