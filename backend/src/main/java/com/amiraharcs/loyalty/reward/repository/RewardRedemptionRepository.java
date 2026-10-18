package com.amiraharcs.loyalty.reward.repository;

import com.amiraharcs.loyalty.reward.model.RewardRedemption;
import java.util.*;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import java.time.Instant;

public interface RewardRedemptionRepository extends Repository<RewardRedemption,UUID>, RewardRedemptionAppend {
    Optional<RewardRedemption> findByIdAndCustomerId(UUID id, UUID customerId);
    // Projection avoids caching a stale entity before the subsequent account/redemption locks.
    interface Target { UUID getCustomerId(); UUID getStoreId(); }
    @Query("select r.customer.id as customerId,r.store.id as storeId from RewardRedemption r where r.id=:id")
    Optional<Target> target(UUID id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RewardRedemption r where r.id=:id")
    Optional<RewardRedemption> lockById(UUID id);
    @Query("""
            select r from RewardRedemption r where r.customer.id=:customerId
            and (:after=false or r.requestedAt < :time or (r.requestedAt=:time and r.id < :id))
            order by r.requestedAt desc,r.id desc
            """)
    List<RewardRedemption> history(UUID customerId,boolean after,Instant time,UUID id,Pageable page);
}
