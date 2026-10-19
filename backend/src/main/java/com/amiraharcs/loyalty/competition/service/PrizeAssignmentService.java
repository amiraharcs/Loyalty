package com.amiraharcs.loyalty.competition.service;

import com.amiraharcs.loyalty.audit.service.AuditService;
import com.amiraharcs.loyalty.auth.security.RoleAuthorities;
import com.amiraharcs.loyalty.common.api.RequestIdFilter;
import com.amiraharcs.loyalty.competition.model.*;
import com.amiraharcs.loyalty.competition.repository.*;
import com.amiraharcs.loyalty.loyalty.repository.StreakRepository;
import com.amiraharcs.loyalty.loyalty.service.LoyaltyAccountService;
import com.amiraharcs.loyalty.reward.repository.RewardRepository;
import com.amiraharcs.loyalty.store.service.StaffStoreScopeService;
import com.amiraharcs.loyalty.visit.model.Visit;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.context.request.*;

/** Internal atomic issuance only: no endpoint, points mutation or weekly assignment writer. */
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class PrizeAssignmentService {
    private final com.amiraharcs.loyalty.auth.service.AuthenticationIdentityService identities;
    private final PrizeRepository prizes;
    private final CustomerPrizeRepository entitlements;
    private final StreakRepository streaks;
    private final LoyaltyAccountService accounts;
    private final RewardRepository rewards;
    private final StaffStoreScopeService scope;
    private final EntityManager entities;
    private final AuditService audit;
    private final Clock clock;
    public PrizeAssignmentService(PrizeRepository prizes,CustomerPrizeRepository entitlements,StreakRepository streaks,
            LoyaltyAccountService accounts,RewardRepository rewards,StaffStoreScopeService scope,EntityManager entities,AuditService audit,Clock clock,com.amiraharcs.loyalty.auth.service.AuthenticationIdentityService identities) {
        this.prizes=prizes;this.entitlements=entitlements;this.streaks=streaks;this.accounts=accounts;this.rewards=rewards;
        this.scope=scope;this.entities=entities;this.audit=audit;this.clock=clock;this.identities=identities;
    }
    @PreAuthorize(RoleAuthorities.REQUIRES_STAFF)
    public Optional<CustomerPrize> assignStreak(UUID visitId) {
        var visit=entities.find(Visit.class,Objects.requireNonNull(visitId));
        if(visit==null) {throw new IllegalArgumentException("Persisted qualifying visit required");}
        UUID customer=visit.getCustomer().getId();
        scope.requireAssignedStore(visit.getStore().getId());UUID actor=scope.currentStaffId();
        if(actor.equals(visit.getCustomer().getUser().getId())) {throw new AccessDeniedException("Access denied");}
        accounts.lockForMutation(customer);
        var grants=identities.activeRoleGrants(visit.getCustomer().getUser().getId());
        if(grants.isEmpty() || !grants.get().contains(com.amiraharcs.loyalty.auth.model.RoleCode.CUSTOMER)) {
            throw new com.amiraharcs.loyalty.common.api.ResourceNotFoundException();
        }
        var previous=entitlements.findByCustomerIdAndTriggerVisitId(customer,visitId);
        if(previous.isPresent()) {return previous;}
        var streak=streaks.findById(customer).orElseThrow();
        Instant now=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if(now.isBefore(visit.getConfirmedAt())) {throw new IllegalStateException("Assignment clock precedes qualifying visit");}
        if(streak.getCurrentCount()==0 || streak.getCurrentCount()%10!=0
                || !visit.getBusinessDate().equals(streak.getLastQualifyingDate())) {
            return Optional.empty();
        }
        var definition=prizes.lockStreakDefinition();
        if(definition.isEmpty()) {
            var attributes=RequestContextHolder.getRequestAttributes();
            UUID correlation=attributes instanceof ServletRequestAttributes servlet
                    ?UUID.fromString(RequestIdFilter.requestId(servlet.getRequest())):UUID.randomUUID();
            audit.append(actor,"STREAK_PRIZE_SKIPPED","VISIT",visitId,now,correlation,"{\"reasonCode\":\"NO_ACTIVE_DEFINITION\"}");
            return Optional.empty();
        }
        var prize=definition.get();
        var reward=rewards.lockById(prize.getRewardId()).orElseThrow();
        var entitlement=CustomerPrize.streak(customer,visitId,prize,reward,now);
        entities.persist(entitlement);entities.flush();
        var request=RequestContextHolder.getRequestAttributes();
        UUID requestId=request instanceof ServletRequestAttributes servlet
                ?UUID.fromString(RequestIdFilter.requestId(servlet.getRequest())):UUID.randomUUID();
        audit.append(actor,"STREAK_PRIZE_ASSIGNED","CUSTOMER_PRIZE",entitlement.getId(),now,requestId,"{}");
        return Optional.of(entitlement);
    }
}
