package com.amiraharcs.loyalty.reward.repository;

import com.amiraharcs.loyalty.reward.model.Reward;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.Repository;

/** No delete or unrestricted write operations: mutations go through the audited reward service. */
public interface RewardRepository extends Repository<Reward, UUID> {
    Optional<Reward> findById(UUID id);
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select r from Reward r where r.id = :id")
    Optional<Reward> lockById(UUID id);
    @Query("""
            select r from Reward r where (:admin = true or (r.active = true
                and (r.availableFrom is null or r.availableFrom <= :now)
                and (r.availableUntil is null or r.availableUntil > :now)))
            and (r.pointCost > :cost or (r.pointCost = :cost and r.name > :name)
                or (r.pointCost = :cost and r.name = :name and r.id > :id))
            order by r.pointCost, r.name, r.id
            """)
    List<Reward> catalogue(boolean admin, Instant now, long cost, String name, UUID id, Pageable page);
}
