package com.amiraharcs.loyalty.game.repository;

import com.amiraharcs.loyalty.game.model.GameLifeConsumption;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Deliberately no update/delete API. One aggregate query gives a consistent read snapshot. */
public interface GameLifeConsumptionRepository extends Repository<GameLifeConsumption, UUID> {
    interface ActiveLives {
        long getUsed();
        Instant getNextRechargeAt();
    }
    @Query("select count(c) as used, min(c.rechargeAt) as nextRechargeAt from GameLifeConsumption c "
            + "where c.customerId = :customerId and c.rechargeAt > :now")
    ActiveLives activeAt(UUID customerId, Instant now);
}
