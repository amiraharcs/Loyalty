package com.amiraharcs.loyalty.loyalty.model;

import com.amiraharcs.loyalty.auth.model.User;
import com.amiraharcs.loyalty.visit.model.Visit;
import com.amiraharcs.loyalty.reward.model.RewardRedemption;
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id", updatable = false)
    private Visit visit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "redemption_id", updatable = false)
    private RewardRedemption redemption;

    protected PointTransaction() { }
    public PointTransaction(LoyaltyAccount account, long signedAmount, PointTransactionType type,
            User actor, String reason, Instant createdAt) {
        this(account, signedAmount, type, actor, reason, createdAt, null, null);
    }

    private PointTransaction(LoyaltyAccount account, long signedAmount, PointTransactionType type,
            User actor, String reason, Instant createdAt, Visit visit, RewardRedemption redemption) {
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
        if (type == PointTransactionType.EARN && (visit == null || actor == null)
                || type != PointTransactionType.EARN && visit != null) {
            throw new IllegalArgumentException("EARN requires its visit and confirming actor");
        }
        this.visit = visit;
        if (type == PointTransactionType.REDEMPTION && (redemption == null || actor == null)
                || type != PointTransactionType.REDEMPTION && redemption != null) {
            throw new IllegalArgumentException("REDEMPTION requires its source and customer actor");
        }
        this.redemption = redemption;
        this.actor = actor;
        this.reason = reason;
    }
    /** Every earning field comes from the persisted immutable visit, never a client amount. */
    public static PointTransaction forVisit(Visit visit) {
        Objects.requireNonNull(visit);
        if (visit.getId() == null) { throw new IllegalArgumentException("Visit must be persisted before earning points"); }
        return new PointTransaction(visit.getAccount(), visit.getEarnedPoints(), PointTransactionType.EARN,
                visit.getConfirmedBy(), null, visit.getConfirmedAt(), visit, null);
    }
    /** Every deduction field comes from the persisted request snapshot, never the current catalogue. */
    public static PointTransaction forRedemption(RewardRedemption redemption) {
        Objects.requireNonNull(redemption);
        if (redemption.getId() == null) { throw new IllegalArgumentException("Redemption must be persisted before its debit"); }
        return new PointTransaction(redemption.getAccount(), -redemption.getPointCostSnapshot(), PointTransactionType.REDEMPTION,
                redemption.getCustomer().getUser(), null, redemption.getRequestedAt(), null, redemption);
    }
    public RewardRedemption getRedemption() { return redemption; }
    public Visit getVisit() { return visit; }
    public UUID getId() { return id; }
    public LoyaltyAccount getAccount() { return account; }
    public long getSignedAmount() { return signedAmount; }
    public PointTransactionType getType() { return type; }
    public User getActor() { return actor; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}
