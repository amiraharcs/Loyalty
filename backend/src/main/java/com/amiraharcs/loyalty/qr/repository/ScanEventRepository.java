package com.amiraharcs.loyalty.qr.repository;

import com.amiraharcs.loyalty.qr.model.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Internal operational queries only; no HTTP route. Every query has an exact anchor, time range and result cap. */
@Repository
@Transactional(readOnly = true)
public class ScanEventRepository {
    private final JdbcTemplate jdbc;
    public ScanEventRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<QrScanEvent> byStaff(UUID staffId, Instant from, Instant to, int limit) {
        return query("staff_user_id",Objects.requireNonNull(staffId),from,to,limit);
    }
    public List<QrScanEvent> byStore(UUID storeId, Instant from, Instant to, int limit) {
        return query("store_id",Objects.requireNonNull(storeId),from,to,limit);
    }
    public List<QrScanEvent> byToken(String jti, Instant from, Instant to, int limit) {
        if (jti == null || !jti.matches("[A-Za-z0-9_-]{43}")) { throw new IllegalArgumentException("Invalid token reference"); }
        return query("token_jti",jti,from,to,limit);
    }
    public List<QrScanEvent> byOutcome(ScanEventOutcome outcome, Instant from, Instant to, int limit) {
        return query("outcome",Objects.requireNonNull(outcome).name(),from,to,limit);
    }
    public List<QrScanEvent> byRequestId(UUID requestId, Instant from, Instant to, int limit) {
        return query("request_id",Objects.requireNonNull(requestId),from,to,limit);
    }
    private List<QrScanEvent> query(String column,Object anchor,Instant from,Instant to,int limit) {
        if (from == null || to == null || !from.isBefore(to) || Duration.between(from,to).compareTo(Duration.ofDays(31))>0
                || limit<1 || limit>100) { throw new IllegalArgumentException("History requires a positive range up to 31 days and 1–100 results"); }
        // Column names come only from the fixed methods above, never from a caller-provided sort/filter string.
        return jdbc.query("SELECT * FROM loyalty.qr_scan_events WHERE "+column+"=? AND occurred_at>=? AND occurred_at<? "
                +"ORDER BY occurred_at DESC,id DESC LIMIT ?",(rs,row)->new QrScanEvent(rs.getObject("id",UUID.class),rs.getString("token_jti"),
                rs.getObject("staff_user_id",UUID.class),rs.getObject("store_id",UUID.class),rs.getTimestamp("occurred_at").toInstant(),
                ScanEventOutcome.valueOf(rs.getString("outcome")),rs.getString("reason_code"),rs.getObject("request_id",UUID.class),
                rs.getObject("resolution_id",UUID.class),rs.getObject("visit_id",UUID.class)),anchor,java.sql.Timestamp.from(from),java.sql.Timestamp.from(to),limit);
    }
}
