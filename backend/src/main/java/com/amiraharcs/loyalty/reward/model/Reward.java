package com.amiraharcs.loyalty.reward.model;

import com.amiraharcs.loyalty.loyalty.model.PointValues;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Mutable catalogue definition. Future redemptions must retain their own benefit/cost snapshot. */
@Entity
@Table(name = "rewards", schema = "loyalty")
public class Reward {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false, length = 200) private String name;
    @Column(nullable = false, length = 2000) private String description;
    @Column(name = "point_cost", nullable = false) private long pointCost;
    @Column(nullable = false) private boolean active;
    @Column(name = "available_from") private Instant availableFrom;
    @Column(name = "available_until") private Instant availableUntil;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version @Column(nullable = false) private long version;

    protected Reward() { }
    public Reward(String name, String description, long pointCost, boolean active, Instant from, Instant until, Instant now) {
        createdAt = Objects.requireNonNull(now);
        revise(name, description, pointCost, active, from, until, now);
    }
    public void revise(String name, String description, long pointCost, boolean active, Instant from, Instant until, Instant now) {
        if (name == null || name.isBlank() || name.length() > 200 || name.indexOf('\0') >= 0
                || description == null || description.length() > 2000 || description.indexOf('\0') >= 0
                || pointCost < 0 || pointCost > PointValues.MAX_SAFE_INTEGER
                || (from != null && until != null && !until.isAfter(from))) {
            throw new IllegalArgumentException("Invalid reward definition");
        }
        Objects.requireNonNull(now);
        if (updatedAt != null && now.isBefore(updatedAt)) { throw new IllegalArgumentException("Update time must not move backwards"); }
        this.name = name; this.description = description; this.pointCost = pointCost; this.active = active;
        this.availableFrom = from; this.availableUntil = until; this.updatedAt = now;
    }
    public boolean isAvailableAt(Instant now) {
        Objects.requireNonNull(now);
        return active && (availableFrom == null || !now.isBefore(availableFrom))
                && (availableUntil == null || now.isBefore(availableUntil));
    }
    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public long getPointCost() { return pointCost; }
    public boolean isActive() { return active; }
    public Instant getAvailableFrom() { return availableFrom; }
    public Instant getAvailableUntil() { return availableUntil; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
