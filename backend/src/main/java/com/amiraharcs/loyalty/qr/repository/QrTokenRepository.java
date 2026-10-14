package com.amiraharcs.loyalty.qr.repository;

import com.amiraharcs.loyalty.qr.model.QrIdentityToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface QrTokenRepository extends Repository<QrIdentityToken, String> {
    Optional<QrIdentityToken> findById(String jti);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from QrIdentityToken token where token.jti=:jti")
    Optional<QrIdentityToken> lockByJti(@Param("jti") String jti);
    @Modifying(flushAutomatically = true)
    @Query("update QrIdentityToken token set token.status = com.amiraharcs.loyalty.qr.model.QrTokenStatus.REVOKED, "
            + "token.revokedAt = :now where token.customer.id = :customerId and token.status = com.amiraharcs.loyalty.qr.model.QrTokenStatus.ACTIVE")
    int revokeCurrent(@Param("customerId") UUID customerId, @Param("now") Instant now);
}
