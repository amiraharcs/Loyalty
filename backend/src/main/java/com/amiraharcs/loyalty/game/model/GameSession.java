package com.amiraharcs.loyalty.game.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Server-created run. Expiry never modifies or refunds its life consumption. */
@Entity
@Table(name = "game_sessions", schema = "loyalty")
public class GameSession {
    @Id @Column(nullable = false, updatable = false) private UUID id;
    @Column(name = "customer_id", nullable = false, updatable = false) private UUID customerId;
    @Column(name = "started_at", nullable = false, updatable = false) private Instant startedAt;
    @Column(name = "expires_at", nullable = false, updatable = false) private Instant expiresAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private GameSessionStatus status;
    @Column(name = "engine_version", nullable = false, updatable = false, length = 40) private String engineVersion;
    @Version @Column(nullable = false) private long version;
    protected GameSession() { }
    public GameSession(UUID customerId, Instant startedAt, Instant expiresAt, String engineVersion) {
        this.id = UUID.randomUUID();
        this.customerId = Objects.requireNonNull(customerId);
        this.startedAt = Objects.requireNonNull(startedAt);
        this.expiresAt = Objects.requireNonNull(expiresAt);
        if (!expiresAt.isAfter(startedAt)) { throw new IllegalArgumentException("Session expiry must follow start"); }
        if (engineVersion == null || !engineVersion.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,39}")) {
            throw new IllegalArgumentException("Invalid server engine version");
        }
        this.engineVersion = engineVersion;
        this.status = GameSessionStatus.ACTIVE;
    }
    public boolean expireIfStale(Instant now) {
        Objects.requireNonNull(now);
        if (status != GameSessionStatus.ACTIVE || now.isBefore(expiresAt)) { return false; }
        status = GameSessionStatus.EXPIRED;
        return true;
    }
    public void complete(Instant now) {
        if(status!=GameSessionStatus.ACTIVE || now.isBefore(startedAt) || !now.isBefore(expiresAt)) {
            throw new IllegalStateException("Session cannot be completed");
        }
        status=GameSessionStatus.COMPLETED;completedAt=now;
    }
    public UUID getId() { return id; }
    public UUID getCustomerId() { return customerId; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCompletedAt() { return completedAt; }
    public GameSessionStatus getStatus() { return status; }
    public String getEngineVersion() { return engineVersion; }
    public long getVersion() { return version; }
}
