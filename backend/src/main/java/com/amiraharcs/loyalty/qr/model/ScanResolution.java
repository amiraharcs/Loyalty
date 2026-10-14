package com.amiraharcs.loyalty.qr.model;

import com.amiraharcs.loyalty.auth.model.User;
import com.amiraharcs.loyalty.customer.model.CustomerProfile;
import com.amiraharcs.loyalty.store.model.Store;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "scan_resolutions", schema = "loyalty")
public class ScanResolution {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "token_jti", nullable = false, updatable = false) private QrIdentityToken token;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false, updatable = false) private CustomerProfile customer;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "staff_user_id", nullable = false, updatable = false) private User staff;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false) private Store store;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "expires_at", nullable = false, updatable = false) private Instant expiresAt;
    @Column(name = "consumed_at") private Instant consumedAt;
    protected ScanResolution() { }
    public ScanResolution(QrIdentityToken token, User staff, Store store, Instant now) {
        this.token = java.util.Objects.requireNonNull(token); this.customer = token.getCustomer();
        this.staff = java.util.Objects.requireNonNull(staff); this.store = java.util.Objects.requireNonNull(store);
        this.createdAt = now; this.expiresAt = now.plusSeconds(120);
    }
    public UUID getId() { return id; }
    public QrIdentityToken getToken() { return token; }
    public CustomerProfile getCustomer() { return customer; }
    public User getStaff() { return staff; }
    public Store getStore() { return store; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }
    public void consume(Instant now) {
        if (consumedAt != null || now.isBefore(createdAt) || !now.isBefore(expiresAt)) { throw new IllegalStateException("Resolution cannot be consumed"); }
        consumedAt = now;
    }
}
