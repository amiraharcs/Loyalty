package com.amiraharcs.loyalty.auth.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Authentication identity, separate from public-facing profile data. Never a response DTO. */
@Entity
@Table(name = "users", schema = "loyalty")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "canonical_email", length = 254, nullable = false, unique = true)
    private String canonicalEmail;
    @JsonIgnore
    @Column(name = "password_hash", length = 512, nullable = false)
    private String passwordHash;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", length = 16, nullable = false)
    private AccountStatus status;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "user_roles", schema = "loyalty",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_code"))
    private Set<Role> roles = new HashSet<>();

    protected User() { }

    public User(String email, String passwordHash, AccountStatus status, Instant now, Set<Role> roles) {
        this.canonicalEmail = canonicalizeEmail(email);
        this.passwordHash = requireNonblank(passwordHash);
        this.status = Objects.requireNonNull(status);
        this.createdAt = Objects.requireNonNull(now);
        this.updatedAt = now;
        this.roles = new HashSet<>(Objects.requireNonNull(roles));
        if (this.roles.contains(null)) { throw new IllegalArgumentException("Role must not be null"); }
    }

    /** Apply the same canonicalization before repository lookups in future auth services. */
    public static String canonicalizeEmail(String email) {
        return requireNonblank(email).strip().toLowerCase(Locale.ROOT);
    }

    private static String requireNonblank(String value) {
        if (value == null || value.isBlank()) { throw new IllegalArgumentException("Required identity value is missing"); }
        return value;
    }

    public UUID getId() { return id; }
    public String getCanonicalEmail() { return canonicalEmail; }
    @JsonIgnore
    public String getPasswordHash() { return passwordHash; }
    public AccountStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Set<Role> getRoles() { return Set.copyOf(roles); }

    /** Persistence mutation only; future service owns authorization and session invalidation. */
    public void setStatus(AccountStatus status, Instant updatedAt) {
        Objects.requireNonNull(updatedAt);
        if (updatedAt.isBefore(this.updatedAt)) { throw new IllegalArgumentException("Update time must not move backwards"); }
        this.status = Objects.requireNonNull(status);
        this.updatedAt = updatedAt;
    }

    // No generated toString/equals/hashCode: credentials never participate in them.
}
