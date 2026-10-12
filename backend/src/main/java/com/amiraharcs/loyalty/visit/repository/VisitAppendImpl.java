package com.amiraharcs.loyalty.visit.repository;

import com.amiraharcs.loyalty.visit.model.Visit;
import jakarta.persistence.EntityManager;
import java.util.Objects;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Only new records may be persisted; no merge or history mutation. */
public class VisitAppendImpl implements VisitAppend {
    private final EntityManager entities;
    public VisitAppendImpl(EntityManager entities) { this.entities = entities; }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public Visit append(Visit visit) {
        Objects.requireNonNull(visit);
        if (visit.getId() != null) { throw new IllegalArgumentException("Only new visits can be appended"); }
        entities.persist(visit);
        entities.flush();
        return visit;
    }
}
