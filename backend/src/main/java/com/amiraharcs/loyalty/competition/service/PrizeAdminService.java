package com.amiraharcs.loyalty.competition.service;

import com.amiraharcs.loyalty.audit.service.AuditService;
import com.amiraharcs.loyalty.auth.security.RoleAuthorities;
import com.amiraharcs.loyalty.common.api.*;
import com.amiraharcs.loyalty.competition.api.*;
import com.amiraharcs.loyalty.competition.model.Prize;
import com.amiraharcs.loyalty.competition.repository.PrizeRepository;
import com.amiraharcs.loyalty.reward.repository.RewardRepository;
import jakarta.persistence.*;
import jakarta.validation.*;
import java.time.*;
import java.util.*;
import org.springframework.dao.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
@PreAuthorize(RoleAuthorities.REQUIRES_ADMIN)
@Transactional(readOnly=true)
public class PrizeAdminService {
    private final PrizeAdminIdentity admins;
    private final PrizeRepository prizes;
    private final RewardRepository rewards;
    private final EntityManager entities;
    private final Validator validator;
    private final AuditService audit;
    private final Clock clock;
    private final JsonMapper json;
    public PrizeAdminService(PrizeAdminIdentity admins,PrizeRepository prizes,RewardRepository rewards,EntityManager entities,
            Validator validator,AuditService audit,Clock clock,JsonMapper json) {
        this.admins=admins;this.prizes=prizes;this.rewards=rewards;this.entities=entities;this.validator=validator;this.audit=audit;this.clock=clock;this.json=json;
    }
    public AdminPrizeResponse get(UUID id) {admins.require();return AdminPrizeResponse.from(require(id));}
    public AdminPrizePage<AdminPrizeResponse> list(int size,String cursor) {
        admins.require();if(size<1 || size>100) {throw new ResponseStatusException(HttpStatus.BAD_REQUEST);}
        var after=PrizeAdminCursor.decode(cursor,"catalogue");
        var rows=prizes.adminHistory(after.after(),after.time(),after.id(),org.springframework.data.domain.PageRequest.of(0,size+1));
        boolean next=rows.size()>size;var page=rows.subList(0,Math.min(size,rows.size()));
        return new AdminPrizePage<>(page.stream().map(AdminPrizeResponse::from).toList(),
                next?PrizeAdminCursor.encode(page.getLast().getCreatedAt(),page.getLast().getId(),"catalogue"):null,next);
    }
    @Transactional public AdminPrizeResponse create(PrizeRequest request,UUID requestId) {
        UUID actor=admins.require();validate(request);reward(request.rewardId());admins.require();
        var now=now();var p=new Prize(request.name(),request.active(),request.benefitType(),request.benefitDescription(),request.benefitValue(),request.rewardId(),request.triggerType(),request.weeklyRank(),now);
        entities.persist(p);flush();
        audit.append(actor,"PRIZE_CREATED","PRIZE",p.getId(),now,requestId,"{}");return AdminPrizeResponse.from(p);
    }
    @Transactional public AdminPrizeResponse update(UUID id,PrizePatchRequest patch,UUID requestId) {
        UUID actor=admins.require();
        if(patch.fields().isEmpty() || patch.expectedVersion()==null || patch.expectedVersion()<0) {throw new ResponseStatusException(HttpStatus.BAD_REQUEST);}
        var p=require(id);if(patch.expectedVersion()!=p.getVersion()) {throw new ConflictException();}
        var request=patch.merge(p);validate(request);
        if(request.triggerType()!=p.getTriggerType() || !Objects.equals(request.weeklyRank(),p.getWeeklyRank())) {throw new ConflictException();}
        reward(request.rewardId());admins.require();boolean previous=p.isActive();var now=now();
        p.revise(request.name(),request.active(),request.benefitType(),request.benefitDescription(),request.benefitValue(),request.rewardId(),now);flush();admins.require();
        String action=previous && !p.isActive()?"PRIZE_DEACTIVATED":!previous && p.isActive()?"PRIZE_ACTIVATED":"PRIZE_UPDATED";
        audit.append(actor,action,"PRIZE",id,now,requestId,json.writeValueAsString(Map.of("fields",patch.fields())));
        return AdminPrizeResponse.from(p);
    }
    private void reward(UUID id) {if(id!=null) {rewards.lockById(id).orElseThrow(ResourceNotFoundException::new);}}
    private void validate(PrizeRequest request) {
        var errors=validator.validate(request);if(!errors.isEmpty()) {throw new ConstraintViolationException(errors);}
    }
    private void flush() {
        try {entities.flush();}
        catch(OptimisticLockException|OptimisticLockingFailureException conflict) {throw new ConflictException();}
        catch(RuntimeException failure) {
            for(Throwable cause=failure;cause!=null;cause=cause.getCause()) {
                if(cause instanceof org.hibernate.exception.ConstraintViolationException c && "23505".equals(c.getSQLState())
                        && Set.of("uq_prize_active_weekly_rank","uq_prize_active_streak").contains(c.getConstraintName())) {throw new ConflictException();}
            }
            throw failure;
        }
    }
    private Prize require(UUID id) {return prizes.findById(id).orElseThrow(ResourceNotFoundException::new);}
    private Instant now() {return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
}
