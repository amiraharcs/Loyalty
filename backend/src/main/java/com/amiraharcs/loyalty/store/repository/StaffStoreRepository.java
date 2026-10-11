package com.amiraharcs.loyalty.store.repository;

import com.amiraharcs.loyalty.store.model.StaffStore;
import com.amiraharcs.loyalty.store.model.StaffStoreId;
import com.amiraharcs.loyalty.store.model.Store;
import com.amiraharcs.loyalty.store.model.StoreStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Query current assignment/store state together; deactivation never deletes a mapping. */
public interface StaffStoreRepository extends Repository<StaffStore, StaffStoreId> {
    StaffStore save(StaffStore assignment);
    Optional<StaffStore> findById(StaffStoreId id);

    @Query("select a.store from StaffStore a where a.id.staffUserId = :staffId "
            + "and a.id.storeId = :storeId and a.active = true and a.store.status = :status")
    Optional<Store> findAssignedStore(@Param("staffId") UUID staffId, @Param("storeId") UUID storeId,
            @Param("status") StoreStatus status);

    @Query("select a.store from StaffStore a where a.id.staffUserId = :staffId "
            + "and a.active = true and a.store.status = :status order by a.store.name, a.store.id")
    List<Store> findAssignedStores(@Param("staffId") UUID staffId, @Param("status") StoreStatus status);
}
