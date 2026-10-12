package com.amiraharcs.loyalty.loyalty.repository;

import com.amiraharcs.loyalty.loyalty.model.PointTransaction;
import jakarta.persistence.EntityManager;
import java.util.Objects;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persist only; deliberately provides no merge, update or delete operation. */
public class PointTransactionAppendImpl implements PointTransactionAppend {
    private final EntityManager entities;
    public PointTransactionAppendImpl(EntityManager entities) { this.entities = entities; }
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PointTransaction append(PointTransaction transaction) {
        Objects.requireNonNull(transaction);
        if (transaction.getId() != null) {
            throw new IllegalArgumentException("Only new point transactions can be appended");
        }
        entities.persist(transaction);
        entities.flush(); // DB enforces immutable history and safe totals before the caller commits.
        // The insert guard advances the aggregate version; keep a managed account in sync.
        if (entities.contains(transaction.getAccount())) { entities.refresh(transaction.getAccount()); }
        return transaction;
    }
}
