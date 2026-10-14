package com.amiraharcs.loyalty.qr.service;

import com.amiraharcs.loyalty.common.api.ApiErrorCode;
import com.amiraharcs.loyalty.qr.model.ScanEventOutcome;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Append-only security events contain only trusted references and stable enum codes, never raw request data. */
@Service
public class ScanEventService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public ScanEventService(JdbcTemplate jdbc,Clock clock) { this.jdbc=jdbc;this.clock=clock; }
    @Transactional(propagation = Propagation.MANDATORY)
    public void success(String jti,UUID staffId,UUID storeId,ScanEventOutcome outcome,UUID requestId,UUID resolutionId,UUID visitId) {
        if (outcome==ScanEventOutcome.REJECTED) { throw new IllegalArgumentException("Success cannot use rejection outcome"); }
        append(jti,staffId,storeId,outcome,null,requestId,resolutionId,visitId);
    }
    /** Called after the business transaction has ended, never while holding its aggregate locks. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failure(UUID staffId,UUID validatedStoreId,ApiErrorCode reason,UUID requestId) {
        append(null,staffId,validatedStoreId,ScanEventOutcome.REJECTED,reason,requestId,null,null);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void confirmationFailure(UUID staffId,UUID resolutionId,ApiErrorCode reason,UUID requestId) {
        var context=jdbc.query("SELECT token_jti,store_id FROM loyalty.scan_resolutions WHERE id=? AND staff_user_id=?",
                (rs,row)->new FailureContext(rs.getString(1),rs.getObject(2,UUID.class)),resolutionId,staffId);
        if (context.isEmpty()) { append(null,staffId,null,ScanEventOutcome.REJECTED,reason,requestId,null,null); }
        else { append(context.getFirst().jti(),staffId,context.getFirst().storeId(),ScanEventOutcome.REJECTED,reason,requestId,resolutionId,null); }
    }
    private record FailureContext(String jti,UUID storeId) { }
    private void append(String jti,UUID staffId,UUID storeId,ScanEventOutcome outcome,ApiErrorCode reason,UUID requestId,UUID resolutionId,UUID visitId) {
        jdbc.update("INSERT INTO loyalty.qr_scan_events(id,token_jti,staff_user_id,store_id,occurred_at,outcome,reason_code,request_id,resolution_id,visit_id) "
                +"VALUES (?, ?, (SELECT id FROM loyalty.users WHERE id=?), ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(),jti,staffId,storeId,java.sql.Timestamp.from(clock.instant()),outcome.name(),reason==null?null:reason.name(),requestId,resolutionId,visitId);
    }
}
