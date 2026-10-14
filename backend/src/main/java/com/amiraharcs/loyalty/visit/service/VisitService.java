package com.amiraharcs.loyalty.visit.service;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.auth.model.User;
import com.amiraharcs.loyalty.auth.security.RoleAuthorities;
import com.amiraharcs.loyalty.auth.service.AuthenticationIdentityService;
import com.amiraharcs.loyalty.common.api.ResourceNotFoundException;
import com.amiraharcs.loyalty.customer.repository.CustomerProfileRepository;
import com.amiraharcs.loyalty.loyalty.service.LoyaltyAccountService;
import com.amiraharcs.loyalty.store.model.Store;
import com.amiraharcs.loyalty.store.service.StaffStoreScopeService;
import com.amiraharcs.loyalty.visit.model.Visit;
import com.amiraharcs.loyalty.visit.config.VisitAwardRule;
import com.amiraharcs.loyalty.loyalty.model.PointValues;
import com.amiraharcs.loyalty.common.api.ConflictException;
import com.amiraharcs.loyalty.visit.repository.VisitRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class VisitService {
    private final VisitRepository visits;
    private final CustomerProfileRepository profiles;
    private final LoyaltyAccountService accounts;
    private final StaffStoreScopeService scope;
    private final AuthenticationIdentityService identities;
    private final EntityManager entities;
    private final Clock clock;
    private final VisitAwardRule awards;
    public VisitService(VisitRepository visits, CustomerProfileRepository profiles, LoyaltyAccountService accounts,
            StaffStoreScopeService scope, AuthenticationIdentityService identities, EntityManager entities, Clock clock, VisitAwardRule awards) {
        this.visits = visits; this.profiles = profiles; this.accounts = accounts;
        this.scope = scope; this.identities = identities; this.entities = entities; this.clock = clock; this.awards = awards;
    }

    /**
     * Internal visit composition. The staff QR workflow supplies a trusted, staff/store-bound resolution.
     * The caller owns the full transaction, source consumption, idempotency and successful audit/event persistence.
     */
    @PreAuthorize(RoleAuthorities.REQUIRES_STAFF)
    @Transactional(propagation = Propagation.MANDATORY)
    public VisitConfirmationResult confirmResolvedCustomer(UUID customerId, UUID storeId) {
        scope.requireAssignedStore(storeId);
        UUID staffId = scope.currentStaffId();
        var customer = profiles.findById(customerId).orElseThrow(ResourceNotFoundException::new);
        if (staffId.equals(customer.getUser().getId())) { throw new AccessDeniedException("Access denied"); }
        var account = accounts.lockForMutation(customerId);
        var grants = identities.activeRoleGrants(customer.getUser().getId()).orElseThrow(ResourceNotFoundException::new);
        if (!grants.contains(RoleCode.CUSTOMER)) { throw new ResourceNotFoundException(); }
        // Recheck scoped eligibility after waiting for the aggregate; no long-lived permission token.
        scope.requireAssignedStore(storeId);
        var confirmedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var businessDate = Visit.businessDateAt(confirmedAt);
        if (visits.existsByCustomerIdAndBusinessDate(customerId, businessDate)) {
            throw new VisitAlreadyRecordedException();
        }
        long award = awards.pointsPerVisit();
        long previousBalance = accounts.currentBalance(customerId);
        try { PointValues.checkedTotal(previousBalance, award); }
        catch (IllegalStateException unsupportedTotal) { throw new ConflictException(); }
        try {
            var visit = visits.append(new Visit(customer, account,
                    entities.getReference(Store.class, storeId), entities.getReference(User.class, staffId), confirmedAt, award));
            long balance = accounts.recordVisitEarn(visit);
            return new VisitConfirmationResult(visit.getId(), visit.getConfirmedAt(), visit.getBusinessDate(), visit.getEarnedPoints(), balance);
        } catch (RuntimeException exception) {
            // Only the named daily-qualification uniqueness conflict becomes the stable domain outcome.
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && "23505".equals(violation.getSQLState())
                        && "uq_visits_customer_business_date".equals(violation.getConstraintName())) {
                    throw new VisitAlreadyRecordedException();
                }
            }
            throw exception;
        }
    }

    /** Internal duplicate check for a consumed source; callers must authorize staff/store/customer first. */
    @PreAuthorize(RoleAuthorities.REQUIRES_STAFF)
    public boolean alreadyQualifiedToday(UUID customerId) {
        return visits.existsByCustomerIdAndBusinessDate(customerId, Visit.businessDateAt(clock.instant()));
    }

    /** Internal read projection; future HTTP wrappers must derive and authorize customer ownership. */
    public Page<VisitSummary> history(UUID customerId, int page, int size) {
        if (page < 0 || size < 1 || size > 100) { throw new IllegalArgumentException("History page size must be between 1 and 100"); }
        accounts.resolve(customerId);
        return visits.findByCustomerIdOrderByConfirmedAtDescIdDesc(customerId, PageRequest.of(page, size)).map(VisitSummary::from);
    }
}
