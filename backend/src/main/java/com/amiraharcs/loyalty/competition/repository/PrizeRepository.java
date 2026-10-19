package com.amiraharcs.loyalty.competition.repository;

import com.amiraharcs.loyalty.competition.model.*;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.Repository;

/** Catalogue reads only; future mutations require a separate audited admin service. */
public interface PrizeRepository extends Repository<Prize,UUID> {
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select p from Prize p where p.triggerType='STREAK_MILESTONE'")
    Optional<Prize> lockStreakDefinition();
    Optional<Prize> findByTriggerTypeAndWeeklyRankAndActiveTrue(PrizeTriggerType trigger,Integer rank);
}
