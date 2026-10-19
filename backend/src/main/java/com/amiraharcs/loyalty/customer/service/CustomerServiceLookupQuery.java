package com.amiraharcs.loyalty.customer.service;

import com.amiraharcs.loyalty.common.api.ResourceNotFoundException;
import com.amiraharcs.loyalty.customer.api.StaffCustomerLookupResponse;
import com.amiraharcs.loyalty.customer.api.StaffCustomerLookupResponse.PendingRedemption;
import com.amiraharcs.loyalty.loyalty.model.PointValues;
import com.amiraharcs.loyalty.visit.model.Visit;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Narrow read projection; no identity entities, contact data, history or mutation repositories. */
@Repository
@Transactional(readOnly = true, propagation = Propagation.MANDATORY)
public class CustomerServiceLookupQuery {
    private static final int PENDING_LIMIT = 5;
    private final JdbcTemplate jdbc;
    public CustomerServiceLookupQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public StaffCustomerLookupResponse find(UUID customerId, UUID storeId, Instant now) {
        var date = Visit.businessDateAt(now);
        var summaries = jdbc.query("""
                SELECT c.display_name,
                       (SELECT COALESCE(SUM(t.signed_amount),0) FROM loyalty.point_transactions t
                        WHERE t.account_id=a.id) AS balance,
                       CASE WHEN s.last_qualifying_date BETWEEN CAST(? AS date)-1 AND CAST(? AS date)
                            THEN s.current_count ELSE 0 END AS current_streak
                FROM loyalty.customer_profiles c
                JOIN loyalty.users u ON u.id=c.user_id AND u.status='ACTIVE'
                JOIN loyalty.user_roles r ON r.user_id=u.id AND r.role_code='CUSTOMER'
                JOIN loyalty.loyalty_accounts a ON a.customer_id=c.id
                LEFT JOIN loyalty.streaks s ON s.customer_id=c.id
                WHERE c.id=?
                """, (rs, row) -> new Summary(rs.getString("display_name"),
                        PointValues.safeInteger(rs.getBigDecimal("balance").toBigIntegerExact()),
                        rs.getInt("current_streak")), date, date, customerId);
        if (summaries.isEmpty()) { throw new ResourceNotFoundException(); }
        var pending = jdbc.query("""
                SELECT id,reward_name_snapshot,reward_description_snapshot,expires_at
                FROM loyalty.reward_redemptions
                WHERE customer_id=? AND store_id=? AND status='PENDING' AND expires_at>? AND requested_at<=?
                ORDER BY requested_at DESC,id DESC LIMIT ?
                """, (rs, row) -> new PendingRedemption(rs.getObject("id", UUID.class),
                        rs.getString("reward_name_snapshot"), rs.getString("reward_description_snapshot"),
                        "PENDING", rs.getTimestamp("expires_at").toInstant()), customerId, storeId,
                        java.sql.Timestamp.from(now), java.sql.Timestamp.from(now), PENDING_LIMIT + 1);
        var summary = summaries.getFirst();
        return new StaffCustomerLookupResponse(summary.displayName(), summary.balance(), summary.streak(),
                pending.stream().limit(PENDING_LIMIT).toList(), pending.size() > PENDING_LIMIT);
    }
    private record Summary(String displayName, long balance, int streak) { }
}
