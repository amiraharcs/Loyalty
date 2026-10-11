package com.amiraharcs.loyalty.store.repository;

import com.amiraharcs.loyalty.store.model.Store;
import com.amiraharcs.loyalty.store.model.StoreStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Retained stores: no hard-delete repository API. */
public interface StoreRepository extends Repository<Store, UUID> {
    Store save(Store store);
    Optional<Store> findById(UUID id);
    Optional<Store> findByIdAndStatus(UUID id, StoreStatus status);
    List<Store> findAllByStatusOrderByNameAscIdAsc(StoreStatus status);
}
