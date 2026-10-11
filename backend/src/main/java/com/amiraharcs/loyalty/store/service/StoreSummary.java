package com.amiraharcs.loyalty.store.service;

import com.amiraharcs.loyalty.store.model.Store;
import java.util.UUID;

/** Safe active-store projection; no assignment or identity details cross the module boundary. */
public record StoreSummary(UUID id, String name, String address) {
    static StoreSummary from(Store store) { return new StoreSummary(store.getId(), store.getName(), store.getAddress()); }
}
