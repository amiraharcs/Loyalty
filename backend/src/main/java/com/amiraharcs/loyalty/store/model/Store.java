package com.amiraharcs.loyalty.store.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A retained shop identity; never an HTTP response entity. */
@Entity
@Table(name = "stores", schema = "loyalty")
public class Store {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "name", length = 200, nullable = false)
    private String name;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", length = 16, nullable = false)
    private StoreStatus status;
    @Column(name = "address", length = 500, nullable = false)
    private String address;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Store() { }

    public Store(String name, String address, StoreStatus status, Instant now) {
        this.name = requiredText(name, 200);
        this.address = requiredText(address, 500);
        this.status = Objects.requireNonNull(status);
        this.createdAt = Objects.requireNonNull(now);
        this.updatedAt = now;
    }

    private static String requiredText(String value, int limit) {
        if (value == null || value.isBlank() || value.length() > limit) {
            throw new IllegalArgumentException("Store text is missing or exceeds its bound");
        }
        return value;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public StoreStatus getStatus() { return status; }
    public String getAddress() { return address; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    /** Persistence lifecycle only; a future management service must authorize and audit changes. */
    public void setStatus(StoreStatus status, Instant now) {
        Objects.requireNonNull(now);
        if (now.isBefore(updatedAt)) { throw new IllegalArgumentException("Update time must not move backwards"); }
        this.status = Objects.requireNonNull(status);
        this.updatedAt = now;
    }
}
