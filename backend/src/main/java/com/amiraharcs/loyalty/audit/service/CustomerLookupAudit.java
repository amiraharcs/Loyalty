package com.amiraharcs.loyalty.audit.service;

import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Independent append-only read evidence. Never accepts an identifier, query text or exception message. */
@Service
public class CustomerLookupAudit {
    public enum Outcome { SUCCESS, NOT_FOUND, STORE_DENIED, INVALID_REQUEST, RATE_LIMITED, FAILED }
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public CustomerLookupAudit(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID actor, UUID trustedStore, UUID resolvedCustomer, UUID requestId, Outcome outcome) {
        // Existing schema requires a non-null target: failed attempts target their own request, never an unverified customer.
        jdbc.update("""
                INSERT INTO loyalty.audit_logs
                    (id,actor_user_id,actor_type,action,target_type,target_id,store_id,occurred_at,request_id,metadata)
                VALUES (?,?,'USER','CUSTOMER_LOOKUP',?,?,?,?,?,CAST(? AS jsonb))
                """, UUID.randomUUID(), actor, resolvedCustomer == null ? "CUSTOMER_LOOKUP_REQUEST" : "CUSTOMER",
                resolvedCustomer == null ? requestId : resolvedCustomer, trustedStore,
                java.sql.Timestamp.from(clock.instant()), requestId, "{\"outcome\":\"" + outcome.name() + "\"}");
    }
}
