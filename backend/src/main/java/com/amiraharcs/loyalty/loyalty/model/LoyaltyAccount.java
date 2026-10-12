package com.amiraharcs.loyalty.loyalty.model;

import com.amiraharcs.loyalty.customer.model.CustomerProfile;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Locking aggregate; the balance exists only in the point ledger. */
@Entity
@Table(name = "loyalty_accounts", schema = "loyalty")
public class LoyaltyAccount {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false, unique = true, updatable = false)
    private CustomerProfile customer;
    @Version @Column(nullable = false)
    private long version;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LoyaltyAccount() { }
    public LoyaltyAccount(CustomerProfile customer, Instant createdAt) {
        this.customer = Objects.requireNonNull(customer);
        this.createdAt = Objects.requireNonNull(createdAt);
    }
    public UUID getId() { return id; }
    public CustomerProfile getCustomer() { return customer; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
}
