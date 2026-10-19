package com.amiraharcs.loyalty.audit.service;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Durable append-only events; no read API or independent transaction for successful mutations. */
@Service
public class AuditService {
    private final JdbcTemplate jdbc;
    public AuditService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(UUID actorUserId, String action, String targetType, UUID targetId, Instant occurredAt,
            UUID requestId, String safeMetadata) {
        appendWithId(UUID.randomUUID(),actorUserId,action,targetType,targetId,occurredAt,requestId,safeMetadata);
    }
    /** Explicit event ID lets an operational transition reference immutable audit evidence atomically. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void appendWithId(UUID id,UUID actorUserId,String action,String targetType,UUID targetId,Instant occurredAt,
            UUID requestId,String safeMetadata) {
        jdbc.update("INSERT INTO loyalty.audit_logs(id,actor_user_id,actor_type,action,target_type,target_id,occurred_at,request_id,metadata) "
                + "VALUES (?,?,'USER',?,?,?,?,?,CAST(? AS jsonb))", id, actorUserId, action, targetType, targetId,
                java.sql.Timestamp.from(occurredAt), requestId, safeMetadata);
    }
}
