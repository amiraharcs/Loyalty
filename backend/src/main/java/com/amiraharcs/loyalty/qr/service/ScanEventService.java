package com.amiraharcs.loyalty.qr.service;

import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Append-only security events contain only safe references/codes, never raw request data. */
@Service
public class ScanEventService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public ScanEventService(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }
    @Transactional(propagation = Propagation.MANDATORY)
    public void success(String jti, UUID staffId, UUID storeId, String outcome, UUID requestId) {
        append(jti, staffId, storeId, outcome, null, requestId);
    }
    /** Called only after the business transaction has ended; its locks have been released. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failure(UUID staffId, UUID storeId, String reason, UUID requestId) {
        append(null, staffId, storeId, "REJECTED", reason, requestId);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void confirmationFailure(UUID staffId, UUID resolutionId, String reason, UUID requestId) {
        var context=jdbc.query("SELECT token_jti,store_id FROM loyalty.scan_resolutions WHERE id=? AND staff_user_id=?",
                (rs,row)->new FailureContext(rs.getString(1),rs.getObject(2,UUID.class)),resolutionId,staffId);
        if (context.isEmpty()) { append(null,staffId,null,"REJECTED",reason,requestId); }
        else { append(context.getFirst().jti(),staffId,context.getFirst().storeId(),"REJECTED",reason,requestId); }
    }
    private record FailureContext(String jti,UUID storeId) { }
    private void append(String jti, UUID staffId, UUID storeId, String outcome, String reason, UUID requestId) {
        jdbc.update("INSERT INTO loyalty.qr_scan_events(id,token_jti,staff_user_id,store_id,occurred_at,outcome,reason_code,request_id) "
                + "VALUES (?, ?, (SELECT id FROM loyalty.users WHERE id=?), (SELECT id FROM loyalty.stores WHERE id=?), ?, ?, ?, ?)",
                UUID.randomUUID(), jti, staffId, storeId, java.sql.Timestamp.from(clock.instant()), outcome, reason, requestId);
    }
}
