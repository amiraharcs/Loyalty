package com.amiraharcs.loyalty.store;

import com.amiraharcs.loyalty.store.model.StaffStoreId;
import com.amiraharcs.loyalty.store.model.Store;
import com.amiraharcs.loyalty.store.model.StoreStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StoreModelTests {
    @Test
    void storeNamesAndAddressesAreRequiredAndBoundedWithoutSilentTruncation() {
        Instant now = Instant.parse("2026-10-11T00:00:00Z");
        assertThat(new Store("n".repeat(200), "a".repeat(500), StoreStatus.ACTIVE, now).getName()).hasSize(200);
        for (String invalid : new String[] {null, "", "   ", "n".repeat(201)}) {
            assertThatThrownBy(() -> new Store(invalid, "Address", StoreStatus.ACTIVE, now)).isInstanceOf(IllegalArgumentException.class);
        }
        for (String invalid : new String[] {null, "", "   ", "a".repeat(501)}) {
            assertThatThrownBy(() -> new Store("Name", invalid, StoreStatus.ACTIVE, now)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new Store("Name", "Address", null, now)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Store("Name", "Address", StoreStatus.ACTIVE, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void lifecycleRetainsCreationTimeAndRejectsBackwardsUpdates() {
        Instant now = Instant.parse("2026-10-11T00:00:00Z");
        var store = new Store("Name", "Address", StoreStatus.ACTIVE, now);
        store.setStatus(StoreStatus.INACTIVE, now.plusSeconds(1));
        assertThat(store.getCreatedAt()).isEqualTo(now);
        assertThat(store.getUpdatedAt()).isEqualTo(now.plusSeconds(1));
        assertThat(store.getStatus()).isEqualTo(StoreStatus.INACTIVE);
        assertThatThrownBy(() -> store.setStatus(StoreStatus.ACTIVE, now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.setStatus(null, now.plusSeconds(2))).isInstanceOf(NullPointerException.class);
        store.setStatus(StoreStatus.ACTIVE, now.plusSeconds(2));
        assertThat(store.getCreatedAt()).isEqualTo(now);
    }

    @Test
    void naturalAssignmentKeyUsesBothIdentifiersWithoutIdentityData() {
        UUID staff = UUID.randomUUID(); UUID store = UUID.randomUUID();
        var key = new StaffStoreId(staff, store);
        assertThat(key).isEqualTo(new StaffStoreId(staff, store));
        assertThat(key.hashCode()).isEqualTo(new StaffStoreId(staff, store).hashCode());
        assertThat(key).isNotEqualTo(new StaffStoreId(UUID.randomUUID(), store));
        assertThat(key).isNotEqualTo(new StaffStoreId(staff, UUID.randomUUID()));
    }
}
