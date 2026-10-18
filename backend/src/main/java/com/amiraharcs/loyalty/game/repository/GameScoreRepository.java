package com.amiraharcs.loyalty.game.repository;

import com.amiraharcs.loyalty.game.model.GameScore;
import java.util.UUID;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface GameScoreRepository extends Repository<GameScore,UUID> {
    Optional<GameScore> findBySessionId(UUID sessionId);
    @Query("select max(g.score) from GameScore g, GameSession s where g.sessionId=s.id and s.customerId=:customerId and s.status='COMPLETED'")
    Long personalBest(UUID customerId);
}
