package com.amiraharcs.loyalty.qr.model;

import com.amiraharcs.loyalty.customer.model.CustomerProfile;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;

/** Persist only the opaque reference and lifecycle data, never the encoded JWS. */
@Entity
@Table(name = "qr_identity_tokens", schema = "loyalty")
public class QrIdentityToken {
    @Id @Column(length = 43, updatable = false)
    private String jti;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false, updatable = false)
    private CustomerProfile customer;
    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;
    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;
    @Column(name = "key_id", nullable = false, length = 64, updatable = false)
    private String keyId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private QrTokenStatus status;
    @Column(name = "revoked_at")
    private Instant revokedAt;
    protected QrIdentityToken() { }
    public QrIdentityToken(String jti, CustomerProfile customer, Instant issuedAt, String keyId) {
        if (jti == null || !jti.matches("[A-Za-z0-9_-]{43}") || keyId == null || !keyId.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("Invalid QR reference metadata");
        }
        this.jti = jti; this.customer = Objects.requireNonNull(customer); this.issuedAt = Objects.requireNonNull(issuedAt);
        this.expiresAt = issuedAt.plusSeconds(60); this.keyId = keyId; this.status = QrTokenStatus.ACTIVE;
    }
    public void consume() {
        if (status != QrTokenStatus.ACTIVE) { throw new IllegalStateException("QR reference cannot be consumed"); }
        status = QrTokenStatus.CONSUMED;
    }
    public String getJti() { return jti; }
    public CustomerProfile getCustomer() { return customer; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getKeyId() { return keyId; }
    public QrTokenStatus getStatus() { return status; }
    public Instant getRevokedAt() { return revokedAt; }
}
