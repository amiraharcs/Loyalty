package com.amiraharcs.loyalty.store.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Natural assignment identity: one retained row for each staff/store pair. */
@Embeddable
public class StaffStoreId implements Serializable {
    private static final long serialVersionUID = 1L;
    @Column(name = "staff_user_id", nullable = false)
    private UUID staffUserId;
    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    protected StaffStoreId() { }

    public StaffStoreId(UUID staffUserId, UUID storeId) {
        this.staffUserId = Objects.requireNonNull(staffUserId);
        this.storeId = Objects.requireNonNull(storeId);
    }
    public UUID getStaffUserId() { return staffUserId; }
    public UUID getStoreId() { return storeId; }
    @Override public boolean equals(Object value) {
        return value instanceof StaffStoreId other
                && Objects.equals(staffUserId, other.staffUserId) && Objects.equals(storeId, other.storeId);
    }
    @Override public int hashCode() { return Objects.hash(staffUserId, storeId); }
}
