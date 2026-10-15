package com.amiraharcs.loyalty.loyalty.repository;

import com.amiraharcs.loyalty.loyalty.model.Streak;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Projection writes belong to StreakService under the customer account lock. */
public interface StreakRepository extends Repository<Streak, UUID> {
    Optional<Streak> findById(UUID customerId);
}
