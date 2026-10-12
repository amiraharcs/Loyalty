package com.amiraharcs.loyalty.loyalty.model;

import com.amiraharcs.loyalty.auth.model.User;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Append-only history. Corrections must be new records, never changes to this entity. */
@Entity @Immutable
@Table(name = "point_transactions", schema = "loyalty")
public class PointTransaction {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private LoyaltyAccount account;
    @Column(name = "signed_amount", nullable = false, updatable = false)
    private long signedAmount;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private PointTransactionType type;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id", updatable = false)
    private User actor;
    @Column(length = 1000, updatable = false)
    private String reason;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PointTransaction() { }
    public PointTransaction(LoyaltyAccount account, long signedAmount, PointTransactionType type,
            User actor, String reason, Instant createdAt) {
        this.account = Objects.requireNonNull(account);
        this.signedAmount = PointValues.amount(signedAmount);
        this.type = Objects.requireNonNull(type);
        this.createdAt = Objects.requireNonNull(createdAt);
        if ((type == PointTransactionType.EARN || type == PointTransactionType.REFUND) && signedAmount < 0
                || type == PointTransactionType.REDEMPTION && signedAmount > 0) {
            throw new IllegalArgumentException("Point amount sign does not match transaction type");
        }
        if (reason != null && (reason.isBlank() || reason.length() > 1000)
                || type == PointTransactionType.ADJUSTMENT && (actor == null || reason == null)) {
            throw new IllegalArgumentException("Adjustment requires an actor and a nonempty reason");
        }
        this.actor = actor;
        this.reason = reason;
    }
    public UUID getId() { return id; }
    public LoyaltyAccount getAccount() { return account; }
    public long getSignedAmount() { return signedAmount; }
    public PointTransactionType getType() { return type; }
    public User getActor() { return actor; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}
