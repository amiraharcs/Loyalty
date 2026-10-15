package com.amiraharcs.loyalty.loyalty.service;

import com.amiraharcs.loyalty.auth.security.RoleAuthorities;
import com.amiraharcs.loyalty.common.api.ConflictException;
import com.amiraharcs.loyalty.loyalty.api.StreakResponse;
import com.amiraharcs.loyalty.loyalty.model.Streak;
import com.amiraharcs.loyalty.loyalty.repository.StreakRepository;
import com.amiraharcs.loyalty.visit.model.Visit;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Internal loyalty collaboration; HTTP ownership is resolved by CustomerStreakService. */
@Service
@Transactional(readOnly = true)
public class StreakService {
    private final StreakRepository streaks;
    private final LoyaltyAccountService accounts;
    private final EntityManager entities;
    public StreakService(StreakRepository streaks,LoyaltyAccountService accounts,EntityManager entities) {
        this.streaks=streaks;this.accounts=accounts;this.entities=entities;
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public Streak createForRegistration(UUID customerId) {
        // The shared scalar PK references a newly registered profile; establish its FK before this INSERT is queued.
        entities.flush();
        var streak=new Streak(customerId);entities.persist(streak);return streak;
    }
    @PreAuthorize(RoleAuthorities.REQUIRES_STAFF)
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordVisit(Visit visit) {
        if (visit.getId()==null) { throw new IllegalArgumentException("Visit must be persisted before updating its projection"); }
        UUID customerId=visit.getCustomer().getId();
        accounts.lockForMutation(customerId); // Already owned by confirmation; retained until the complete workflow commits.
        var streak=require(customerId);
        if (streak.getLastQualifyingDate()!=null && visit.getBusinessDate().isBefore(streak.getLastQualifyingDate())) {
            throw new ConflictException(); // Do not silently corrupt a projection with an out-of-order historical command.
        }
        streak.recordQualifyingDate(visit.getBusinessDate());
        entities.flush(); // Projection/optimistic-lock failure must roll back Visit/EARN in the caller's transaction.
    }
    public StreakResponse snapshot(UUID customerId,LocalDate serverDate) {
        var streak=require(customerId);
        return new StreakResponse(streak.displayedCurrent(serverDate),streak.getLongestCount(),streak.getLastQualifyingDate(),serverDate);
    }
    private Streak require(UUID customerId) {
        return streaks.findById(customerId).orElseThrow(()->new IllegalStateException("Required customer streak projection is missing"));
    }
}
