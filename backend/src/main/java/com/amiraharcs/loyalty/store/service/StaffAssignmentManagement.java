package com.amiraharcs.loyalty.store.service;

import com.amiraharcs.loyalty.admin.api.AdminAccountDtos.Assignment;
import com.amiraharcs.loyalty.audit.service.AuditService;
import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.auth.security.RoleAuthorities;
import com.amiraharcs.loyalty.auth.service.AuthenticationIdentityService;
import com.amiraharcs.loyalty.common.api.*;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Store-owned writes. Identity management holds the authority lock and target-user row first. */
@Service
@PreAuthorize(RoleAuthorities.REQUIRES_ADMIN)
@Transactional(propagation = Propagation.MANDATORY)
public class StaffAssignmentManagement {
    private final JdbcTemplate jdbc;
    private final AuthenticationIdentityService identities;
    private final AuditService audit;
    private final Clock clock;
    public StaffAssignmentManagement(JdbcTemplate jdbc, AuthenticationIdentityService identities, AuditService audit, Clock clock) {
        this.jdbc=jdbc;this.identities=identities;this.audit=audit;this.clock=clock;
    }
    public Assignment assign(UUID actor,UUID staff,UUID store,UUID requestId) {
        requireStaff(staff);requireStore(store);var existing=find(staff,store);
        if(!existing.isEmpty()) {
            if(existing.getFirst().active()) {return existing.getFirst();}
            jdbc.update("UPDATE loyalty.staff_store SET active=true,updated_at=? WHERE staff_user_id=? AND store_id=?",now(),staff,store);
            event(actor,"STAFF_STORE_REACTIVATED",staff,store,requestId);
        } else {
            var at=now();
            jdbc.update("INSERT INTO loyalty.staff_store(staff_user_id,store_id,active,assigned_by,assigned_at,updated_at) VALUES (?,?,true,?,?,?)",staff,store,actor,at,at);
            event(actor,"STAFF_STORE_ASSIGNED",staff,store,requestId);
        }
        return find(staff,store).getFirst();
    }
    public Assignment patch(UUID actor,UUID staff,UUID store,boolean active,UUID requestId) {
        var previous=find(staff,store);if(previous.isEmpty()) {throw new ResourceNotFoundException();}
        if(active) {requireStaff(staff);requireStore(store);}
        if(previous.getFirst().active()==active) {return previous.getFirst();}
        jdbc.update("UPDATE loyalty.staff_store SET active=?,updated_at=? WHERE staff_user_id=? AND store_id=?",active,now(),staff,store);
        event(actor,active?"STAFF_STORE_REACTIVATED":"STAFF_STORE_DEACTIVATED",staff,store,requestId);
        return find(staff,store).getFirst();
    }
    public void deactivateAll(UUID actor,UUID user,UUID requestId) {
        var stores=jdbc.query("UPDATE loyalty.staff_store SET active=false,updated_at=? WHERE staff_user_id=? AND active RETURNING store_id",(rs,row)->rs.getObject(1,UUID.class),now(),user);
        for(UUID store:stores) {event(actor,"STAFF_STORE_DEACTIVATED",user,store,requestId);}
    }
    private void requireStaff(UUID user) {
        if(!identities.activeRoleGrants(user).orElse(Set.of()).contains(RoleCode.STAFF)) {throw new ConflictException();}
    }
    private void requireStore(UUID id) {
        var statuses=jdbc.query("SELECT status FROM loyalty.stores WHERE id=? FOR SHARE",(rs,row)->rs.getString(1),id);
        if(statuses.isEmpty()) {throw new ResourceNotFoundException();}
        if(!statuses.getFirst().equals("ACTIVE")) {throw new ConflictException();}
    }
    private List<Assignment> find(UUID staff,UUID store) {
        return jdbc.query("SELECT * FROM loyalty.staff_store WHERE staff_user_id=? AND store_id=?",(rs,row)->new Assignment(staff,store,rs.getBoolean("active"),rs.getObject("assigned_by",UUID.class),
                rs.getTimestamp("assigned_at").toInstant(),rs.getTimestamp("updated_at").toInstant()),staff,store);
    }
    private java.sql.Timestamp now() {return java.sql.Timestamp.from(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));}
    private void event(UUID actor,String action,UUID user,UUID store,UUID requestId) {
        audit.append(actor,action,"USER",user,now().toInstant(),requestId,"{\"storeId\":\""+store+"\"}");
    }
}
