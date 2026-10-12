package com.amiraharcs.loyalty.loyalty.repository;

import com.amiraharcs.loyalty.loyalty.model.LoyaltyAccount;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface LoyaltyAccountRepository extends Repository<LoyaltyAccount, UUID> {
    LoyaltyAccount save(LoyaltyAccount account);
    Optional<LoyaltyAccount> findByCustomerId(UUID customerId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from LoyaltyAccount account where account.customer.id = :customerId")
    Optional<LoyaltyAccount> lockByCustomerId(@Param("customerId") UUID customerId);
}
