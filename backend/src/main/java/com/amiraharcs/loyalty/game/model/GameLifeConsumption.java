package com.amiraharcs.loyalty.game.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Immutable evidence. Null session links exist only on preserved pre-V17 history. */
@Entity
@Immutable
@Table(name = "game_life_consumptions", schema = "loyalty")
public class GameLifeConsumption {
    @Id @Column(nullable = false, updatable = false) private UUID id;
    @Column(name = "customer_id", nullable = false, updatable = false) private UUID customerId;
    @Column(name = "consumed_at", nullable = false, updatable = false) private Instant consumedAt;
    @Column(name = "recharge_at", nullable = false, updatable = false) private Instant rechargeAt;
    @Column(name = "session_id", updatable = false) private UUID sessionId;
    protected GameLifeConsumption() { }
    public GameLifeConsumption(UUID customerId, UUID sessionId, Instant consumedAt, Instant rechargeAt) {
        this.id = UUID.randomUUID();
        this.sessionId = Objects.requireNonNull(sessionId);
        this.customerId = Objects.requireNonNull(customerId);
        this.consumedAt = Objects.requireNonNull(consumedAt);
        this.rechargeAt = Objects.requireNonNull(rechargeAt);
        if (!rechargeAt.isAfter(consumedAt)) { throw new IllegalArgumentException("Recharge must follow consumption"); }
    }
    public UUID getSessionId() { return sessionId; }
    public UUID getId() { return id; }
    public UUID getCustomerId() { return customerId; }
    public Instant getConsumedAt() { return consumedAt; }
    public Instant getRechargeAt() { return rechargeAt; }
}
