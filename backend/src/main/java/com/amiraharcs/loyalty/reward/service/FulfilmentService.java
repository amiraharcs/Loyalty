package com.amiraharcs.loyalty.reward.service;

import com.amiraharcs.loyalty.audit.service.AuditService;
import com.amiraharcs.loyalty.auth.model.*;
import com.amiraharcs.loyalty.auth.security.RoleAuthorities;
import com.amiraharcs.loyalty.auth.service.AuthenticationIdentityService;
import com.amiraharcs.loyalty.common.api.*;
import com.amiraharcs.loyalty.common.idempotency.IdempotencyService;
import com.amiraharcs.loyalty.loyalty.service.LoyaltyAccountService;
import com.amiraharcs.loyalty.reward.api.FulfilmentResponse;
import com.amiraharcs.loyalty.reward.model.RedemptionStatus;
import com.amiraharcs.loyalty.reward.repository.RewardRedemptionRepository;
import com.amiraharcs.loyalty.store.service.StaffStoreScopeService;
import jakarta.persistence.EntityManager;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import tools.jackson.databind.json.JsonMapper;

@Service
@Validated
@PreAuthorize(RoleAuthorities.REQUIRES_STAFF)
public class FulfilmentService {
    private static final String OPERATION="STAFF_REDEMPTION_FULFILMENT";
    private final RewardRedemptionRepository redemptions;
    private final LoyaltyAccountService accounts;
    private final StaffStoreScopeService scope;
    private final AuthenticationIdentityService identities;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final EntityManager entities;
    private final Clock clock;
    private final JsonMapper json;
    public FulfilmentService(RewardRedemptionRepository redemptions,LoyaltyAccountService accounts,StaffStoreScopeService scope,
            AuthenticationIdentityService identities,IdempotencyService idempotency,AuditService audit,EntityManager entities,Clock clock,JsonMapper json) {
        this.redemptions=redemptions;this.accounts=accounts;this.scope=scope;this.identities=identities;
        this.idempotency=idempotency;this.audit=audit;this.entities=entities;this.clock=clock;this.json=json;
    }
    @Transactional
    public FulfilmentResponse fulfil(@NotNull UUID id,@NotNull UUID key,@NotNull UUID requestId) {
        UUID staff=scope.currentStaffId();String hash=IdempotencyService.hash(id.toString());
        var previous=idempotency.previous(staff,OPERATION,key,hash);
        var target=redemptions.target(id).orElseThrow(ResourceNotFoundException::new);
        scope.requireAssignedStore(target.getStoreId());
        accounts.lockForMutation(target.getCustomerId());
        var row=redemptions.lockById(id).orElseThrow(ResourceNotFoundException::new);
        scope.currentStaffId();scope.requireAssignedStore(row.getStore().getId());
        UUID owner=row.getCustomer().getUser().getId();
        if(owner.equals(staff)) { throw new AccessDeniedException("Access denied"); }
        var grants=identities.activeRoleGrants(owner).orElseThrow(ResourceNotFoundException::new);
        if(!grants.contains(RoleCode.CUSTOMER)) { throw new ResourceNotFoundException(); }
        if(previous.isPresent()) { return json.readValue(previous.get(),FulfilmentResponse.class); }
        var now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        if(row.getStatus()!=RedemptionStatus.FULFILLED) {
            if(row.getStatus()!=RedemptionStatus.PENDING || !now.isBefore(row.getExpiresAt()) || now.isBefore(row.getRequestedAt())) {
                throw new ConflictException();
            }
            row.fulfil(entities.getReference(User.class,staff),now);entities.flush();
            audit.append(staff,"REDEMPTION_FULFILLED","REWARD_REDEMPTION",id,now,requestId,
                    json.writeValueAsString(Map.of("storeId",row.getStore().getId(),"customerId",row.getCustomer().getId(),"rewardId",row.getReward().getId())));
        }
        var response=FulfilmentResponse.from(row);
        idempotency.complete(staff,OPERATION,key,hash,json.writeValueAsString(response),now);
        return response;
    }
}
