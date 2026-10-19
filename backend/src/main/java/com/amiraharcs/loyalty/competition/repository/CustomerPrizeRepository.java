package com.amiraharcs.loyalty.competition.repository;

import com.amiraharcs.loyalty.competition.model.CustomerPrize;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface CustomerPrizeRepository extends Repository<CustomerPrize,UUID> {
    Optional<CustomerPrize> findById(UUID id);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from CustomerPrize p where p.id=:id")
    Optional<CustomerPrize> lockById(UUID id);
    Optional<CustomerPrize> findByCustomerIdAndTriggerVisitId(UUID customerId,UUID visitId);
    @Query("""
        select p from CustomerPrize p where p.customerId=:owner
        and (:after=false or p.awardedAt<:time or (p.awardedAt=:time and p.id<:id))
        order by p.awardedAt desc,p.id desc
        """)
    List<CustomerPrize> history(UUID owner,boolean after,Instant time,UUID id,Pageable page);
    @Query("select p from CustomerPrize p where p.customerId=:owner and p.status='AVAILABLE' and p.expiresAt>:now order by p.expiresAt,p.id")
    List<CustomerPrize> available(UUID owner,Instant now,Pageable page);
}
