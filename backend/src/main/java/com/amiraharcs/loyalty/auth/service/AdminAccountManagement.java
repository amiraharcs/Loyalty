package com.amiraharcs.loyalty.auth.service;

import com.amiraharcs.loyalty.admin.api.AdminAccountDtos.*;
import com.amiraharcs.loyalty.audit.service.AuditService;
import com.amiraharcs.loyalty.auth.model.*;
import com.amiraharcs.loyalty.auth.security.RoleAuthorities;
import com.amiraharcs.loyalty.auth.service.AdminAuthorityService;
import com.amiraharcs.loyalty.common.api.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** All authority/assignment writes take the same PostgreSQL transaction lock before row locks. */
@Service
@PreAuthorize(RoleAuthorities.REQUIRES_ADMIN)
@Transactional
public class AdminAccountManagement {
    private static final long AUTHORITY_LOCK = 0x4c4f59414c555345L;
    private final JdbcTemplate jdbc;
    private final AdminAuthorityService admins;
    private final AuditService audit;
    private final Clock clock;
    private final com.amiraharcs.loyalty.store.service.StaffAssignmentManagement assignments;
    public AdminAccountManagement(JdbcTemplate jdbc, AdminAuthorityService admins, AuditService audit, Clock clock, com.amiraharcs.loyalty.store.service.StaffAssignmentManagement assignments) {
        this.jdbc=jdbc; this.admins=admins; this.audit=audit; this.clock=clock; this.assignments=assignments;
    }
    private UUID lock(UUID target) {
        admins.require();
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var statement=connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                statement.setLong(1,AUTHORITY_LOCK);statement.execute();return null;
            }
        });
        UUID actor=admins.require();
        if (jdbc.query("SELECT id FROM loyalty.users WHERE id=? FOR UPDATE",(rs,row)->rs.getObject(1,UUID.class),target).isEmpty()) {
            throw new ResourceNotFoundException();
        }
        return actor;
    }
    public UserState status(UUID target, AccountStatus desired, UUID requestId) {
        if (desired==null) {throw new InvalidRequestException();}
        UUID actor=lock(target);var before=state(target);
        if (before.status()==desired) {return before;}
        if (desired==AccountStatus.DISABLED && before.roles().contains(RoleCode.ADMIN)) {requireOtherAdmin(target);}
        jdbc.update("UPDATE loyalty.users SET status=?,updated_at=? WHERE id=?",desired.name(),now(),target);
        if (desired==AccountStatus.DISABLED) {assignments.deactivateAll(actor,target,requestId);}
        revokeSessions(target);
        event(actor,desired==AccountStatus.DISABLED?"USER_DISABLED":"USER_ACTIVATED",target,requestId,"{}");
        return state(target);
    }
    public UserState role(UUID target, RoleCode role, boolean grant, UUID requestId) {
        if (role==null) {throw new InvalidRequestException();}
        UUID actor=lock(target);var before=state(target);
        if (before.roles().contains(role)==grant) {return before;}
        if (!grant && role==RoleCode.ADMIN && before.status()==AccountStatus.ACTIVE) {requireOtherAdmin(target);}
        if (grant && role==RoleCode.CUSTOMER && jdbc.queryForObject("SELECT count(*) FROM loyalty.customer_profiles WHERE user_id=?",Long.class,target)==0) {
            throw new ConflictException(); // Customer onboarding owns profile/account/lives creation.
        }
        if (grant) {jdbc.update("INSERT INTO loyalty.user_roles(user_id,role_code) VALUES (?,?)",target,role.name());}
        else {jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=? AND role_code=?",target,role.name());}
        if (!grant && role==RoleCode.STAFF) {assignments.deactivateAll(actor,target,requestId);}
        jdbc.update("UPDATE loyalty.users SET updated_at=? WHERE id=?",now(),target);
        revokeSessions(target);
        event(actor,grant?"ROLE_GRANTED":"ROLE_REVOKED",target,requestId,"{\"role\":\""+role.name()+"\"}");
        return state(target);
    }
    public Assignment assign(UUID staff, UUID store, UUID requestId) {
        UUID actor=lock(staff);return assignments.assign(actor,staff,store,requestId);
    }
    public Assignment assignment(UUID staff, UUID store, boolean active, UUID requestId) {
        UUID actor=lock(staff);return assignments.patch(actor,staff,store,active,requestId);
    }
    private void requireOtherAdmin(UUID target) {
        if (jdbc.queryForObject("SELECT count(*) FROM loyalty.users u JOIN loyalty.user_roles r ON r.user_id=u.id WHERE u.status='ACTIVE' AND r.role_code='ADMIN' AND u.id<>?",Long.class,target)==0) {
            throw new LastAdminRequiredException();
        }
    }
    private UserState state(UUID id) {
        var status=jdbc.queryForObject("SELECT status FROM loyalty.users WHERE id=?",String.class,id);
        var roles=jdbc.query("SELECT role_code FROM loyalty.user_roles WHERE user_id=? ORDER BY role_code",(rs,row)->RoleCode.valueOf(rs.getString(1)),id);
        return new UserState(id,AccountStatus.valueOf(status),List.copyOf(roles));
    }
    private void revokeSessions(UUID user) {
        // Spring Session's FK cascades transient attributes only; domain history is untouched.
        jdbc.update("DELETE FROM loyalty.spring_session WHERE principal_name=?",user.toString());
    }
    private java.sql.Timestamp now() {return java.sql.Timestamp.from(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));}
    private void event(UUID actor,String action,UUID target,UUID requestId,String metadata) {
        audit.append(actor,action,"USER",target,now().toInstant(),requestId,metadata);
    }
}
