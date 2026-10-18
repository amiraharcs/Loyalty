package com.amiraharcs.loyalty.reward.model;

import com.amiraharcs.loyalty.auth.model.User;
import com.amiraharcs.loyalty.customer.model.CustomerProfile;
import com.amiraharcs.loyalty.loyalty.model.*;
import com.amiraharcs.loyalty.store.model.Store;
import jakarta.persistence.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Retained request evidence. Catalogue edits never change its benefit or price snapshots. */
@Entity
@Table(name = "reward_redemptions", schema = "loyalty")
public class RewardRedemption {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false, updatable = false) private CustomerProfile customer;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, updatable = false) private LoyaltyAccount account;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false) private Store store;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reward_id", nullable = false, updatable = false) private Reward reward;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16, updatable = false) private RedemptionSource source;
    @Column(name = "reward_name_snapshot", nullable = false, length = 200, updatable = false) private String rewardNameSnapshot;
    @Column(name = "reward_description_snapshot", nullable = false, length = 2000, updatable = false) private String rewardDescriptionSnapshot;
    @Column(name = "point_cost_snapshot", nullable = false, updatable = false) private long pointCostSnapshot;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private RedemptionStatus status;
    @Column(name = "requested_at", nullable = false, updatable = false) private Instant requestedAt;
    @Column(name = "expires_at", nullable = false, updatable = false) private Instant expiresAt;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "fulfilled_by") private User fulfilledBy;
    @Column(name = "fulfilled_at") private Instant fulfilledAt;
    @Version @Column(nullable = false) private long version;

    protected RewardRedemption() { }
    public RewardRedemption(CustomerProfile customer, LoyaltyAccount account, Reward reward, Store store, Instant now) {
        this.customer = Objects.requireNonNull(customer); this.account = Objects.requireNonNull(account);
        this.reward = Objects.requireNonNull(reward); this.store = Objects.requireNonNull(store);
        if (customer.getId() == null || !customer.getId().equals(account.getCustomer().getId())
                || reward.getId() == null || store.getId() == null || reward.getPointCost() <= 0) {
            throw new IllegalArgumentException("Points redemption requires owned account and a persisted positive-cost reward/store");
        }
        this.pointCostSnapshot = PointValues.amount(reward.getPointCost());
        this.rewardNameSnapshot = reward.getName(); this.rewardDescriptionSnapshot = reward.getDescription();
        this.source = RedemptionSource.POINTS; this.status = RedemptionStatus.PENDING;
        this.requestedAt = Objects.requireNonNull(now).truncatedTo(ChronoUnit.MICROS);
        this.expiresAt = requestedAt.plus(Duration.ofHours(24));
    }
    public UUID getId() { return id; }
    public CustomerProfile getCustomer() { return customer; }
    public LoyaltyAccount getAccount() { return account; }
    public Store getStore() { return store; }
    public Reward getReward() { return reward; }
    public RedemptionSource getSource() { return source; }
    public String getRewardNameSnapshot() { return rewardNameSnapshot; }
    public String getRewardDescriptionSnapshot() { return rewardDescriptionSnapshot; }
    public long getPointCostSnapshot() { return pointCostSnapshot; }
    public RedemptionStatus getStatus() { return status; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public User getFulfilledBy() { return fulfilledBy; }
    public Instant getFulfilledAt() { return fulfilledAt; }
    public long getVersion() { return version; }
}
