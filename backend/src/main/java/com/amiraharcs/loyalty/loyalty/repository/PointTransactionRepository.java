package com.amiraharcs.loyalty.loyalty.repository;

import com.amiraharcs.loyalty.loyalty.model.PointTransaction;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface PointTransactionRepository extends Repository<PointTransaction, UUID>, PointTransactionAppend {
    // PostgreSQL SUM(bigint) widens exactly. Read its integer text without a narrowing/decimal conversion.
    @Query(value = "SELECT COALESCE(SUM(signed_amount), 0)::text FROM loyalty.point_transactions WHERE account_id = :accountId", nativeQuery = true)
    String sumByAccountId(@Param("accountId") UUID accountId);
    Page<PointTransaction> findByAccountIdOrderByCreatedAtDescIdDesc(UUID accountId, Pageable pageable);
}
