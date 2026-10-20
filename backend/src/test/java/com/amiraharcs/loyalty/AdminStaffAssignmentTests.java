package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class AdminStaffAssignmentTests extends AdminAccountTestSupport {
    @Test void assignDeactivateReactivateRetainsOriginalGrantAndAuditsTransitions() throws Exception {
        var admin=login(RoleCode.ADMIN);var member=login(RoleCode.CUSTOMER);var staff=login(RoleCode.STAFF);var shop=store(admin);
        assign(admin,staff,shop.getId(),200);staffLookup(staff,member,shop.getId(),200);
        var original=jdbc.queryForMap("SELECT assigned_by,assigned_at FROM loyalty.staff_store WHERE staff_user_id=? AND store_id=?",staff.userId,shop.getId());
        assign(admin,staff,shop.getId(),200);assertThat(events(staff.userId,"STAFF_STORE_ASSIGNED")).isEqualTo(1);
        assignment(admin,staff,shop.getId(),false,200);staffLookup(staff,member,shop.getId(),404);
        assign(admin,staff,shop.getId(),200);staffLookup(staff,member,shop.getId(),200);
        assertThat(jdbc.queryForMap("SELECT assigned_by,assigned_at FROM loyalty.staff_store WHERE staff_user_id=? AND store_id=?",staff.userId,shop.getId())).isEqualTo(original);
        assertThat(events(staff.userId,"STAFF_STORE_DEACTIVATED")).isEqualTo(1);assertThat(events(staff.userId,"STAFF_STORE_REACTIVATED")).isEqualTo(1);
        var metadata=jdbc.queryForList("SELECT metadata::text FROM loyalty.audit_logs WHERE target_id=?",String.class,staff.userId);
        assertThat(metadata).allSatisfy(text->assertThat(text).doesNotContain("password","email","session","csrf"));
    }
    @ParameterizedTest @ValueSource(strings={"CUSTOMER","DISABLED_STAFF","INACTIVE_STORE","MISSING_STORE"})
    void invalidAssignmentTargetsAreRejected(String scenario) throws Exception {
        var admin=login(RoleCode.ADMIN);var staff=login(scenario.equals("CUSTOMER")?RoleCode.CUSTOMER:RoleCode.STAFF);var shop=store(admin);
        if(scenario.equals("DISABLED_STAFF")) {status(admin,staff.userId,"DISABLED",200);}
        if(scenario.equals("INACTIVE_STORE")) {jdbc.update("UPDATE loyalty.stores SET status='INACTIVE' WHERE id=?",shop.getId());}
        assign(admin,staff,scenario.equals("MISSING_STORE")?UUID.randomUUID():shop.getId(),scenario.equals("MISSING_STORE")?404:409);
        assertThat(events(staff.userId,"STAFF_STORE_ASSIGNED")).isZero();
    }
    @Test void inactiveStoreStillBlocksAnAssignedStaffMember() throws Exception {
        var admin=login(RoleCode.ADMIN);var staff=login(RoleCode.STAFF);var member=login(RoleCode.CUSTOMER);var shop=store(staff);
        jdbc.update("UPDATE loyalty.stores SET status='INACTIVE' WHERE id=?",shop.getId());staffLookup(staff,member,shop.getId(),404);
        assignment(admin,staff,shop.getId(),false,200);assignment(admin,staff,shop.getId(),true,409);
    }
    @Test void missingAssignmentIs404() throws Exception {
        var admin=login(RoleCode.ADMIN);var staff=login(RoleCode.STAFF);assignment(admin,staff,UUID.randomUUID(),false,404);
    }
    @Test void staffRemovalAndDisableDeactivateAssignmentsAtomically() throws Exception {
        var admin=login(RoleCode.ADMIN);var staff=login(RoleCode.STAFF);var shop=store(staff);
        role(admin,staff.userId,"STAFF",false,200);
        assertThat(jdbc.queryForObject("SELECT active FROM loyalty.staff_store WHERE staff_user_id=? AND store_id=?",Boolean.class,staff.userId,shop.getId())).isFalse();
        role(admin,staff.userId,"STAFF",true,200);assign(admin,staff,shop.getId(),200);status(admin,staff.userId,"DISABLED",200);
        assertThat(jdbc.queryForObject("SELECT active FROM loyalty.staff_store WHERE staff_user_id=? AND store_id=?",Boolean.class,staff.userId,shop.getId())).isFalse();
    }
}
