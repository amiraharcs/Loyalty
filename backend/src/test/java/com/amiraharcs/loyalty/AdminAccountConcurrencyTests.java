package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.*;
import com.amiraharcs.loyalty.common.api.LastAdminRequiredException;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class AdminAccountConcurrencyTests extends AdminAccountTestSupport {
    @ParameterizedTest @ValueSource(strings={"DISABLE","REVOKE","MIXED"})
    void concurrentChangesCannotRemoveBothFinalAdministrators(String operation) throws Exception {
        var one=login(RoleCode.ADMIN);var two=login(RoleCode.ADMIN);
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var futures=new ArrayList<Future<String>>();int index=0;
            for(var admin:List.of(one,two)) {boolean disable=operation.equals("DISABLE") || operation.equals("MIXED") && index++==0;
                futures.add(pool.submit(()->{assume(admin,RoleCode.ADMIN);ready.countDown();start.await(10,TimeUnit.SECONDS);
                    try {if(disable) {management.status(admin.userId,AccountStatus.DISABLED,UUID.randomUUID());}
                        else {management.role(admin.userId,RoleCode.ADMIN,false,UUID.randomUUID());}return "OK";
                    } catch(LastAdminRequiredException conflict) {return "LAST_ADMIN_REQUIRED";}
                    finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
                }));
            }
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(List.of(futures.get(0).get(15,TimeUnit.SECONDS),futures.get(1).get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder("OK","LAST_ADMIN_REQUIRED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.users u JOIN loyalty.user_roles r ON r.user_id=u.id WHERE u.status='ACTIVE' AND r.role_code='ADMIN'",Long.class)).isEqualTo(1);
            assertThat(events(one.userId,"USER_DISABLED")+events(two.userId,"USER_DISABLED")+events(one.userId,"ROLE_REVOKED")+events(two.userId,"ROLE_REVOKED")).isEqualTo(1);
        } finally {start.countDown();pool.shutdownNow();}
    }
    @Test void concurrentStaffRemovalAndAssignmentCannotLeaveAnActiveNonStaffAssignment() throws Exception {
        var admin=login(RoleCode.ADMIN);var staff=login(RoleCode.STAFF);var shop=store(admin);
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var revoke=pool.submit(()->{assume(admin,RoleCode.ADMIN);ready.countDown();start.await(10,TimeUnit.SECONDS);
                try {return management.role(staff.userId,RoleCode.STAFF,false,UUID.randomUUID());}
                finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}});
            var assign=pool.submit(()->{assume(admin,RoleCode.ADMIN);ready.countDown();start.await(10,TimeUnit.SECONDS);
                try {management.assign(staff.userId,shop.getId(),UUID.randomUUID());return "OK";}
                catch(com.amiraharcs.loyalty.common.api.ConflictException denied) {return "CONFLICT";}
                finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}});
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();revoke.get(15,TimeUnit.SECONDS);
            assertThat(assign.get(15,TimeUnit.SECONDS)).isIn("OK","CONFLICT");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.staff_store WHERE staff_user_id=? AND active",Long.class,staff.userId)).isZero();
        } finally {start.countDown();pool.shutdownNow();}
    }
    @Test void concurrentAssignmentCreatesOneLogicalPairAndOneAudit() throws Exception {
        var one=login(RoleCode.ADMIN);var two=login(RoleCode.ADMIN);var staff=login(RoleCode.STAFF);var shop=store(one);
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var futures=new ArrayList<Future<?>>();
            for(var admin:List.of(one,two)) {futures.add(pool.submit(()->{assume(admin,RoleCode.ADMIN);ready.countDown();
                try {start.await(10,TimeUnit.SECONDS);return management.assign(staff.userId,shop.getId(),UUID.randomUUID());}
                finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
            }));}
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();for(var future:futures) {future.get(15,TimeUnit.SECONDS);}
            assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.staff_store WHERE staff_user_id=? AND store_id=?",Long.class,staff.userId,shop.getId())).isEqualTo(1);
            assertThat(events(staff.userId,"STAFF_STORE_ASSIGNED")).isEqualTo(1);
        } finally {start.countDown();pool.shutdownNow();}
    }
}
