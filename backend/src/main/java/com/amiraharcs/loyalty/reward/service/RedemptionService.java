package com.amiraharcs.loyalty.reward.service;

import com.amiraharcs.loyalty.audit.service.AuditService;
import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.auth.security.*;
import com.amiraharcs.loyalty.auth.service.AuthenticationIdentityService;
import com.amiraharcs.loyalty.common.api.*;
import com.amiraharcs.loyalty.common.idempotency.IdempotencyService;
import com.amiraharcs.loyalty.customer.model.CustomerProfile;
import com.amiraharcs.loyalty.customer.repository.CustomerProfileRepository;
import com.amiraharcs.loyalty.loyalty.model.PointValues;
import com.amiraharcs.loyalty.loyalty.service.LoyaltyAccountService;
import com.amiraharcs.loyalty.reward.api.*;
import com.amiraharcs.loyalty.reward.model.RewardRedemption;
import com.amiraharcs.loyalty.reward.repository.*;
import com.amiraharcs.loyalty.store.model.Store;
import com.amiraharcs.loyalty.store.service.StoreService;
import jakarta.persistence.EntityManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;
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
@PreAuthorize(RoleAuthorities.REQUIRES_CUSTOMER)
@Transactional(readOnly = true)
public class RedemptionService {
    private static final String OPERATION = "CUSTOMER_REWARD_REDEMPTION";
    private final AuthenticationIdentityService identities;
    private final CustomerProfileRepository profiles;
    private final LoyaltyAccountService accounts;
    private final RewardRepository rewards;
    private final StoreService stores;
    private final RewardRedemptionRepository redemptions;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final EntityManager entities;
    private final Clock clock;
    private final JsonMapper json;
    public RedemptionService(AuthenticationIdentityService identities,CustomerProfileRepository profiles,LoyaltyAccountService accounts,
            RewardRepository rewards,StoreService stores,RewardRedemptionRepository redemptions,IdempotencyService idempotency,
            AuditService audit,EntityManager entities,Clock clock,JsonMapper json) {
        this.identities=identities;this.profiles=profiles;this.accounts=accounts;this.rewards=rewards;this.stores=stores;
        this.redemptions=redemptions;this.idempotency=idempotency;this.audit=audit;this.entities=entities;this.clock=clock;this.json=json;
    }
    @Transactional
    public RedemptionResponse redeem(@NotNull @Valid RedemptionRequest request,@NotNull UUID key,@NotNull UUID requestId) {
        UUID actor = currentCustomerId();
        var customer = profile(actor);
        String payloadHash = IdempotencyService.hash(json.writeValueAsString(List.of(request.rewardId(),request.storeId())));
        // Infrastructure retry lock precedes domain locks, consistently with existing visit/adjustment workflows.
        var previous = idempotency.previous(actor,OPERATION,key,payloadHash);
        var account = accounts.lockForMutation(customer.getId());
        currentCustomerId(); // Current account status/grants after a possibly blocked account lock.
        if (previous.isPresent()) { return json.readValue(previous.get(),RedemptionResponse.class); }
        var reward = rewards.lockById(request.rewardId()).orElseThrow(ResourceNotFoundException::new);
        var store = stores.requireActiveForRedemption(request.storeId());
        currentCustomerId();
        // Capture the server instant only after all locks, so waiting cannot extend reward availability.
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (!reward.isAvailableAt(now)) { throw new ResourceNotFoundException(); }
        // Zero-cost definitions are catalogue data; this POINTS workflow always needs a negative ledger source.
        if (reward.getPointCost() <= 0) { throw new ConflictException(); }
        long balance = accounts.currentBalance(customer.getId());
        if (balance < reward.getPointCost()) { throw new InsufficientPointsException(); }
        long expected = PointValues.checkedTotal(balance,-reward.getPointCost());
        var redemption = redemptions.append(new RewardRedemption(customer,account,reward,
                entities.getReference(Store.class,store.id()),now));
        long newBalance = accounts.recordRedemptionDebit(redemption);
        if (newBalance != expected || newBalance < 0) { throw new IllegalStateException("Redemption ledger total does not match its expected result"); }
        var response = RedemptionResponse.from(redemption,newBalance);
        audit.append(actor,"REWARD_REDEEMED","REWARD_REDEMPTION",redemption.getId(),now,requestId,
                json.writeValueAsString(Map.of("customerId",customer.getId(),"rewardId",reward.getId(),"storeId",store.id(),"pointCost",redemption.getPointCostSnapshot())));
        idempotency.complete(actor,OPERATION,key,payloadHash,json.writeValueAsString(response),now);
        return response;
    }
    public RedemptionDetails details(@NotNull UUID id) {
        var customer = profile(currentCustomerId());
        return redemptions.findByIdAndCustomerId(id,customer.getId()).map(RedemptionDetails::from).orElseThrow(ResourceNotFoundException::new);
    }
    public RedemptionHistoryPage history(int size,String cursor) {
        var customer=profile(currentCustomerId());
        if(size<1 || size>100) { throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST); }
        var after=RedemptionHistoryCursor.decode(cursor,customer.getId());
        var rows=redemptions.history(customer.getId(),after.after(),after.time(),after.id(),org.springframework.data.domain.PageRequest.of(0,size+1));
        boolean hasNext=rows.size()>size;var visible=rows.subList(0,Math.min(size,rows.size()));
        return new RedemptionHistoryPage(visible.stream().map(RedemptionDetails::from).toList(),
                hasNext?RedemptionHistoryCursor.encode(visible.getLast(),customer.getId()):null,hasNext);
    }
    private CustomerProfile profile(UUID actor) { return profiles.findByUserId(actor).orElseThrow(ResourceNotFoundException::new); }
    private UUID currentCustomerId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof SessionPrincipal principal)) {
            throw new BadCredentialsException("Authentication failed");
        }
        var grants = identities.activeRoleGrants(principal.userId()).orElseThrow(()->new BadCredentialsException("Authentication failed"));
        if (!grants.contains(RoleCode.CUSTOMER)) { throw new AccessDeniedException("Access denied"); }
        return principal.userId();
    }
}
