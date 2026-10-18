package com.amiraharcs.loyalty.reward.repository;

import com.amiraharcs.loyalty.reward.model.RewardRedemption;
import jakarta.persistence.EntityManager;
import java.util.Objects;
import org.springframework.transaction.annotation.*;

/** Persist new requests only, inside the transaction that also appends their immutable debit. */
public class RewardRedemptionAppendImpl implements RewardRedemptionAppend {
    private final EntityManager entities;
    public RewardRedemptionAppendImpl(EntityManager entities) { this.entities = entities; }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public RewardRedemption append(RewardRedemption redemption) {
        Objects.requireNonNull(redemption);
        if (redemption.getId() != null) { throw new IllegalArgumentException("Only new redemptions can be appended"); }
        entities.persist(redemption); entities.flush(); return redemption;
    }
}
