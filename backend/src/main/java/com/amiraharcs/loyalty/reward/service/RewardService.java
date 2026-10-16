package com.amiraharcs.loyalty.reward.service;

import com.amiraharcs.loyalty.audit.service.AuditService;
import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.auth.security.*;
import com.amiraharcs.loyalty.auth.service.AuthenticationIdentityService;
import com.amiraharcs.loyalty.common.api.*;
import com.amiraharcs.loyalty.reward.api.*;
import com.amiraharcs.loyalty.reward.model.Reward;
import com.amiraharcs.loyalty.reward.repository.RewardRepository;
import jakarta.persistence.*;
import jakarta.validation.*;
import jakarta.validation.constraints.NotNull;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
@Validated
@Transactional(readOnly = true)
public class RewardService {
    public static final String READ_CATALOGUE = "hasAnyAuthority('ROLE_CUSTOMER','ROLE_STAFF')";
    private final RewardRepository rewards;
    private final EntityManager entities;
    private final Validator validator;
    private final AuthenticationIdentityService identities;
    private final AuditService audit;
    private final Clock clock;
    private final JsonMapper json;
    public RewardService(RewardRepository rewards, EntityManager entities, Validator validator,
            AuthenticationIdentityService identities, AuditService audit, Clock clock, JsonMapper json) {
        this.rewards = rewards; this.entities = entities; this.validator = validator;
        this.identities = identities; this.audit = audit; this.clock = clock; this.json = json;
    }
    @PreAuthorize(READ_CATALOGUE)
    public RewardPage<RewardResponse> catalogue(int size, String cursor) {
        actor(RoleCode.CUSTOMER,RoleCode.STAFF);
        return page(size,cursor,false,RewardResponse::from);
    }
    @PreAuthorize(RoleAuthorities.REQUIRES_ADMIN)
    public RewardPage<AdminRewardResponse> adminCatalogue(int size, String cursor) {
        actor(RoleCode.ADMIN);
        return page(size,cursor,true,AdminRewardResponse::from);
    }
    @PreAuthorize(RoleAuthorities.REQUIRES_ADMIN)
    public AdminRewardResponse adminReward(@NotNull UUID id) {
        actor(RoleCode.ADMIN); return AdminRewardResponse.from(require(id));
    }
    @PreAuthorize(RoleAuthorities.REQUIRES_ADMIN)
    @Transactional
    public AdminRewardResponse create(@NotNull @Valid RewardRequest request, @NotNull UUID requestId) {
        UUID actor = actor(RoleCode.ADMIN);
        var now = now();
        var reward = new Reward(request.name(),request.description(),request.pointCost().longValueExact(),
                request.active(),request.availableFrom(),request.availableUntil(),now);
        entities.persist(reward); entities.flush();
        audit.append(actor,"REWARD_CREATED","REWARD",reward.getId(),now,requestId,"{}");
        return AdminRewardResponse.from(reward);
    }
    @PreAuthorize(RoleAuthorities.REQUIRES_ADMIN)
    @Transactional
    public AdminRewardResponse update(@NotNull UUID id, @NotNull RewardPatchRequest patch, @NotNull UUID requestId) {
        UUID actor = actor(RoleCode.ADMIN);
        if (patch.fields().isEmpty()) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
        var reward = require(id);
        var request = patch.merge(reward);
        var violations = validator.validate(request);
        if (!violations.isEmpty()) { throw new ConstraintViolationException(violations); }
        boolean deactivated = reward.isActive() && !request.active();
        var now = now();
        reward.revise(request.name(),request.description(),request.pointCost().longValueExact(),request.active(),
                request.availableFrom(),request.availableUntil(),now);
        try { entities.flush(); }
        catch (OptimisticLockException | OptimisticLockingFailureException conflict) { throw new ConflictException(); }
        actor(RoleCode.ADMIN); // Recheck grants after any wait for the conflicting writer's database lock.
        // No bodies or free text in audit metadata; changed field names are the fixed DTO allowlist.
        audit.append(actor,deactivated ? "REWARD_DEACTIVATED" : "REWARD_UPDATED","REWARD",id,now,requestId,
                json.writeValueAsString(Map.of("fields",patch.fields())));
        return AdminRewardResponse.from(reward);
    }
    private <T> RewardPage<T> page(int size, String cursor, boolean admin, Function<Reward,T> projection) {
        if (size < 1 || size > 100) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
        var after = RewardCursor.decode(cursor,admin);
        var rows = rewards.catalogue(admin,now(),after.cost(),after.name(),after.id(),PageRequest.of(0,size+1));
        boolean hasNext = rows.size() > size;
        var visible = rows.subList(0,Math.min(size,rows.size()));
        return new RewardPage<>(visible.stream().map(projection).toList(),
                hasNext ? RewardCursor.encode(visible.getLast(),admin) : null,hasNext);
    }
    private Reward require(UUID id) { return rewards.findById(id).orElseThrow(ResourceNotFoundException::new); }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private UUID actor(RoleCode... permitted) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof SessionPrincipal principal)) {
            throw new BadCredentialsException("Authentication failed");
        }
        var grants = identities.activeRoleGrants(principal.userId()).orElseThrow(() -> new BadCredentialsException("Authentication failed"));
        if (Arrays.stream(permitted).noneMatch(grants::contains)) { throw new AccessDeniedException("Access denied"); }
        return principal.userId();
    }
}
