package com.amiraharcs.loyalty.store.repository;

import com.amiraharcs.loyalty.store.model.Store;
import com.amiraharcs.loyalty.store.model.StoreStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

/** Retained stores: no hard-delete repository API. */
public interface StoreRepository extends Repository<Store, UUID> {
    Store save(Store store);
    Optional<Store> findById(UUID id);
    Optional<Store> findByIdAndStatus(UUID id, StoreStatus status);
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select store from Store store where store.id = :id and store.status = :status")
    Optional<Store> lockByIdAndStatus(UUID id, StoreStatus status);
    List<Store> findAllByStatusOrderByNameAscIdAsc(StoreStatus status);
}
