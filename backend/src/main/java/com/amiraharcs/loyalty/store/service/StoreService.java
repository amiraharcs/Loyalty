package com.amiraharcs.loyalty.store.service;

import com.amiraharcs.loyalty.auth.security.RoleAuthorities;
import com.amiraharcs.loyalty.common.api.ResourceNotFoundException;
import com.amiraharcs.loyalty.store.model.StoreStatus;
import com.amiraharcs.loyalty.store.repository.StoreRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly = true)
@PreAuthorize(RoleAuthorities.REQUIRES_AUTHENTICATION)
public class StoreService {
    private final StoreRepository stores;
    public StoreService(StoreRepository stores) { this.stores = stores; }

    public StoreSummary requireActive(UUID storeId) {
        return stores.findByIdAndStatus(storeId, StoreStatus.ACTIVE).map(StoreSummary::from)
                .orElseThrow(ResourceNotFoundException::new);
    }
    public List<StoreSummary> activeStores() {
        return stores.findAllByStatusOrderByNameAscIdAsc(StoreStatus.ACTIVE).stream().map(StoreSummary::from).toList();
    }
    /** Account lock precedes this shared configuration lock, retained through redemption commit. */
    @PreAuthorize(RoleAuthorities.REQUIRES_CUSTOMER)
    @Transactional(propagation = Propagation.MANDATORY)
    public StoreSummary requireActiveForRedemption(UUID id) {
        return stores.lockByIdAndStatus(id,StoreStatus.ACTIVE).map(StoreSummary::from).orElseThrow(ResourceNotFoundException::new);
    }
}
