package com.amiraharcs.loyalty.game.repository;

import com.amiraharcs.loyalty.game.model.GameLifeState;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface GameLifeStateRepository extends Repository<GameLifeState, UUID> {
    Optional<GameLifeState> findById(UUID customerId);

    // Force the version forward even though the aggregate has no mutable lives counter.
    @Lock(LockModeType.PESSIMISTIC_FORCE_INCREMENT)
    @Query("select state from GameLifeState state where state.customerId = :customerId")
    Optional<GameLifeState> lockForConsumption(UUID customerId);
}
