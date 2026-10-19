package com.amiraharcs.loyalty.competition.repository;

import com.amiraharcs.loyalty.competition.model.*;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.Repository;

/** Narrow catalogue reads; mutations belong to the audited, version-checked admin service. */
public interface PrizeRepository extends Repository<Prize,UUID> {
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select p from Prize p where p.triggerType='STREAK_MILESTONE' and p.active=true")
    Optional<Prize> lockStreakDefinition();
    Optional<Prize> findById(UUID id);
    @Query("select p from Prize p where (:after=false or p.createdAt<:time or (p.createdAt=:time and p.id<:id)) order by p.createdAt desc,p.id desc")
    List<Prize> adminHistory(boolean after,java.time.Instant time,UUID id,org.springframework.data.domain.Pageable page);
    Optional<Prize> findByTriggerTypeAndWeeklyRankAndActiveTrue(PrizeTriggerType trigger,Integer rank);
}
