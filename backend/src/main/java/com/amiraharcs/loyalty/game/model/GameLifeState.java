package com.amiraharcs.loyalty.game.model;

import jakarta.persistence.*;
import java.util.Objects;
import java.util.UUID;

/** Aggregate lock; consumption history, rather than a mutable counter, determines lives. */
@Entity
@Table(name = "game_life_state", schema = "loyalty")
public class GameLifeState {
    @Id @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;
    @Version @Column(nullable = false) private long version;
    protected GameLifeState() { }
    public GameLifeState(UUID customerId) { this.customerId = Objects.requireNonNull(customerId); }
    public UUID getCustomerId() { return customerId; }
    public long getVersion() { return version; }
}
