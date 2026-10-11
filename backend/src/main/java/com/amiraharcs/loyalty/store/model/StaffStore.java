package com.amiraharcs.loyalty.store.model;

import com.amiraharcs.loyalty.auth.model.User;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import org.springframework.data.domain.Persistable;

/** Explicit scope, independent of role grants; associations never cascade persistence/deletion. */
@Entity
@Table(name = "staff_store", schema = "loyalty")
public class StaffStore implements Persistable<StaffStoreId> {
    @EmbeddedId
    private StaffStoreId id;
    @MapsId("staffUserId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "staff_user_id", nullable = false, updatable = false)
    private User staffUser;
    @MapsId("storeId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;
    @Column(name = "active", nullable = false)
    private boolean active;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assigned_by", nullable = false, updatable = false)
    private User assignedBy;
    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Transient
    private boolean newRecord = true;

    protected StaffStore() { }

    public StaffStore(User staffUser, Store store, User assignedBy, Instant now) {
        this.staffUser = Objects.requireNonNull(staffUser);
        this.store = Objects.requireNonNull(store);
        this.assignedBy = Objects.requireNonNull(assignedBy);
        if (staffUser.getId() == null || store.getId() == null || assignedBy.getId() == null) {
            throw new IllegalArgumentException("Assignment references must already be persisted");
        }
        this.id = new StaffStoreId(staffUser.getId(), store.getId());
        this.active = true;
        this.assignedAt = Objects.requireNonNull(now);
        this.updatedAt = now;
    }

    @Override public StaffStoreId getId() { return id; }
    @Override public boolean isNew() { return newRecord; }
    @PostLoad @PostPersist
    private void markPersisted() { newRecord = false; }
    public User getStaffUser() { return staffUser; }
    public Store getStore() { return store; }
    public boolean isActive() { return active; }
    public User getAssignedBy() { return assignedBy; }
    public Instant getAssignedAt() { return assignedAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    /** Retain original grant metadata; later management/audit services own actor tracking. */
    public void setActive(boolean active, Instant now) {
        Objects.requireNonNull(now);
        if (now.isBefore(updatedAt)) { throw new IllegalArgumentException("Update time must not move backwards"); }
        this.active = active;
        this.updatedAt = now;
    }
}
