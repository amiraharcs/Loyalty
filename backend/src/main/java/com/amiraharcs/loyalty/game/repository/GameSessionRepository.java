package com.amiraharcs.loyalty.game.repository;

import com.amiraharcs.loyalty.game.model.GameSession;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** All mutation locks follow the customer's GameLifeState lock. No delete operation. */
public interface GameSessionRepository extends Repository<GameSession, UUID> {
    boolean existsByIdAndCustomerId(UUID id, UUID customerId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from GameSession s where s.customerId = :customerId and s.status = 'ACTIVE'")
    Optional<GameSession> lockActive(UUID customerId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from GameSession s where s.id = :id and s.customerId = :customerId")
    Optional<GameSession> lockOwned(UUID id, UUID customerId);
}
