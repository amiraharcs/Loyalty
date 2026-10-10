package com.amiraharcs.loyalty.customer.repository;

import com.amiraharcs.loyalty.customer.model.CustomerProfile;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

public interface CustomerProfileRepository extends Repository<CustomerProfile, UUID> {
    CustomerProfile save(CustomerProfile profile);
    Optional<CustomerProfile> findById(UUID id);
    Optional<CustomerProfile> findByUserId(UUID userId);
}
