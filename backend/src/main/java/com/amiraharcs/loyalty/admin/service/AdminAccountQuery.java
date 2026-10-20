package com.amiraharcs.loyalty.admin.service;

import com.amiraharcs.loyalty.admin.api.AdminAccountDtos.*;
import com.amiraharcs.loyalty.auth.model.*;
import com.amiraharcs.loyalty.auth.service.AdminAuthorityService;
import com.amiraharcs.loyalty.auth.security.RoleAuthorities;
import com.amiraharcs.loyalty.common.api.*;
import com.amiraharcs.loyalty.loyalty.model.PointValues;
import com.amiraharcs.loyalty.visit.model.Visit;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize(RoleAuthorities.REQUIRES_ADMIN)
@Transactional(readOnly = true)
public class AdminAccountQuery {
    private final JdbcTemplate jdbc;
    private final AdminAuthorityService admins;
    private final Clock clock;
    public AdminAccountQuery(JdbcTemplate jdbc, AdminAuthorityService admins, Clock clock) {
        this.jdbc = jdbc; this.admins = admins; this.clock = clock;
    }
    private static final String ROLES = "ARRAY(SELECT role_code FROM loyalty.user_roles WHERE user_id=u.id ORDER BY role_code) AS roles";
    private String customerSql() {
        return "SELECT c.id AS customer_id,u.id,c.display_name,u.status,u.created_at," + ROLES + ","
            + "(SELECT COALESCE(SUM(t.signed_amount),0) FROM loyalty.point_transactions t JOIN loyalty.loyalty_accounts a ON a.id=t.account_id WHERE a.customer_id=c.id) AS balance,"
            + "CASE WHEN s.last_qualifying_date BETWEEN CAST(? AS date)-1 AND CAST(? AS date) THEN s.current_count ELSE 0 END AS current_streak "
            + "FROM loyalty.customer_profiles c JOIN loyalty.users u ON u.id=c.user_id LEFT JOIN loyalty.streaks s ON s.customer_id=c.id ";
    }
    private static final String STAFF_SQL = "SELECT u.id,c.display_name,u.status,u.created_at," + ROLES
            + ",ARRAY(SELECT store_id FROM loyalty.staff_store WHERE staff_user_id=u.id AND active ORDER BY store_id) AS store_ids "
            + "FROM loyalty.users u LEFT JOIN loyalty.customer_profiles c ON c.user_id=u.id ";
    private static final String STAFF_SCOPE = "(EXISTS(SELECT 1 FROM loyalty.user_roles r WHERE r.user_id=u.id AND r.role_code='STAFF') "
            + "OR EXISTS(SELECT 1 FROM loyalty.staff_store a WHERE a.staff_user_id=u.id))";
    public Customer customer(UUID id) {
        admins.require();var date=day(); return jdbc.query(customerSql()+"WHERE c.id=?", this::customerRow, date, date, id)
                .stream().findFirst().orElseThrow(ResourceNotFoundException::new);
    }
    public Staff staff(UUID id) {
        admins.require(); return jdbc.query(STAFF_SQL+"WHERE u.id=? AND "+STAFF_SCOPE, this::staffRow, id)
                .stream().findFirst().orElseThrow(ResourceNotFoundException::new);
    }
    public Page<Customer> customers(int size, String cursor, AccountStatus status, UUID customerId) {
        admins.require(); return list(false,size,cursor,status,customerId,this::customerRow);
    }
    public Page<Staff> staff(int size, String cursor, AccountStatus status, UUID userId) {
        admins.require(); return list(true,size,cursor,status,userId,this::staffRow);
    }
    private <T> Page<T> list(boolean staff, int size, String cursor, AccountStatus status, UUID id, RowMapper<T> mapper) {
        if (size < 1 || size > 100) { throw new InvalidRequestException(); }
        String scope = (staff?"staff:":"customers:")+status+":"+id;
        var after = AdminAccountCursor.decode(cursor,scope);
        String sql = (staff?STAFF_SQL:customerSql())+"WHERE "+(staff?STAFF_SCOPE:"true");
        var args = new ArrayList<Object>();
        if (!staff) {var date=day();args.add(date);args.add(date);}
        if (status != null) { sql += " AND u.status=?"; args.add(status.name()); }
        if (id != null) { sql += staff?" AND u.id=?":" AND c.id=?"; args.add(id); }
        if (after.after()) { sql += " AND (u.created_at,u.id)<(?,?)"; args.add(java.sql.Timestamp.from(after.time())); args.add(after.id()); }
        sql += " ORDER BY u.created_at DESC,u.id DESC LIMIT ?"; args.add(size+1);
        var rows = jdbc.query(sql,mapper,args.toArray());
        boolean more = rows.size()>size; var items = List.copyOf(rows.subList(0,Math.min(size,rows.size())));
        String next = null;
        if (more) {
            Object last = items.getLast();
            Instant time = last instanceof Staff s?s.createdAt():((Customer)last).createdAt();
            UUID user = last instanceof Staff s?s.userId():((Customer)last).userId();
            next = AdminAccountCursor.encode(time,user,scope);
        }
        return new Page<>(items,next,more);
    }
    private LocalDate day() {return Visit.businessDateAt(clock.instant());}
    private Customer customerRow(ResultSet rs,int row) throws SQLException {
        return new Customer(rs.getObject("customer_id",UUID.class),rs.getObject("id",UUID.class),rs.getString("display_name"),
                AccountStatus.valueOf(rs.getString("status")),rs.getTimestamp("created_at").toInstant(),roles(rs),
                PointValues.safeInteger(rs.getBigDecimal("balance").toBigIntegerExact()),rs.getInt("current_streak"));
    }
    private Staff staffRow(ResultSet rs,int row) throws SQLException {
        return new Staff(rs.getObject("id",UUID.class),rs.getString("display_name"),AccountStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(),roles(rs),List.of((UUID[])rs.getArray("store_ids").getArray()));
    }
    private List<RoleCode> roles(ResultSet rs) throws SQLException {
        return Arrays.stream((String[])rs.getArray("roles").getArray()).map(RoleCode::valueOf).toList();
    }
}
