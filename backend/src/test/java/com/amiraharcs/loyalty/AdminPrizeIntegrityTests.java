package com.amiraharcs.loyalty;
import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.competition.api.*;
import com.amiraharcs.loyalty.competition.model.*;
import com.amiraharcs.loyalty.competition.service.PrizeAssignmentAdminService;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.annotation.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class AdminPrizeIntegrityTests extends AdminPrizeTestSupport {
    private PrizeRequest request(String name) {return new PrizeRequest(name,false,PrizeBenefitType.REWARD,"One coffee",null,COFFEE,PrizeTriggerType.STREAK_MILESTONE,null);}
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void catalogueAuditFailureRollsBackBothCreationAndUpdate() throws Exception {
        var admin=login(RoleCode.ADMIN);assume(admin,RoleCode.ADMIN);String name="Rollback "+UUID.randomUUID();
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("Synthetic audit failure");}).when(AopTestUtils.<com.amiraharcs.loyalty.audit.service.AuditService>getUltimateTargetObject(audit)).append(eq(admin.userId),anyString(),eq("PRIZE"),any(),any(),any(),anyString());
        try {assertThatThrownBy(()->catalogue.create(request(name),UUID.randomUUID())).isInstanceOf(IllegalStateException.class);}
        finally {reset(audit);}
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.prizes WHERE name=?",Long.class,name)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.audit_logs WHERE actor_user_id=?",Long.class,admin.userId)).isZero();
        var p=catalogue.create(request(name),UUID.randomUUID());var patch=new PrizePatchRequest();patch.setExpectedVersion(0L);patch.setName("Must roll back");
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("Synthetic audit failure");}).when(AopTestUtils.<com.amiraharcs.loyalty.audit.service.AuditService>getUltimateTargetObject(audit)).append(eq(admin.userId),eq("PRIZE_UPDATED"),eq("PRIZE"),any(),any(),any(),anyString());
        try {assertThatThrownBy(()->catalogue.update(p.id(),patch,UUID.randomUUID())).isInstanceOf(IllegalStateException.class);}
        finally {reset(audit);}
        assertThat(catalogue.get(p.id()).name()).isEqualTo(name);assertThat(catalogue.get(p.id()).version()).isZero();
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void failureAfterStatusFlushRollsBackRevocationAndItsAudit() throws Exception {
        var setup=setup();days(setup,10);var admin=login(RoleCode.ADMIN);
        UUID id=jdbc.queryForObject("SELECT id FROM loyalty.customer_prizes WHERE customer_id=?",UUID.class,customer(setup.customer()));
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("Synthetic failure after revoke");})
                .when(AopTestUtils.<PrizeAssignmentAdminService>getUltimateTargetObject(assignmentAdmin)).revoke(any(),any(),any());
        try {admin.send(post(ASSIGNMENTS+"/"+id+"/revocations").header("X-CSRF-TOKEN",admin.csrf).contentType("application/json").content("{\"reason\":\"Mistaken award\"}"),500);}
        finally {reset(assignmentAdmin);}
        assertThat(jdbc.queryForObject("SELECT status FROM loyalty.customer_prizes WHERE id=?",String.class,id)).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT revocation_audit_id FROM loyalty.customer_prizes WHERE id=?",UUID.class,id)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.audit_logs WHERE target_id=? AND action='CUSTOMER_PRIZE_REVOKED'",Long.class,id)).isZero();
        assertThat(balance(setup.customer())).isEqualTo(1000);
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void simultaneousRevocationRetriesProduceOneAuditAndNoPointsChange() throws Exception {
        var setup=setup();days(setup,10);var admin=login(RoleCode.ADMIN);
        UUID id=jdbc.queryForObject("SELECT id FROM loyalty.customer_prizes WHERE customer_id=?",UUID.class,customer(setup.customer()));
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Integer> call=()->{gate.await();return mvc.perform(post(ASSIGNMENTS+"/"+id+"/revocations").cookie(admin.cookies.values().toArray(jakarta.servlet.http.Cookie[]::new))
                    .header("X-CSRF-TOKEN",admin.csrf).contentType("application/json").content("{\"reason\":\"Concurrent correction\"}")).andReturn().getResponse().getStatus();};
            var a=pool.submit(call);var b=pool.submit(call);gate.countDown();assertThat(a.get(30,TimeUnit.SECONDS)).isEqualTo(200);assertThat(b.get(30,TimeUnit.SECONDS)).isEqualTo(200);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.audit_logs WHERE target_id=? AND action='CUSTOMER_PRIZE_REVOKED'",Long.class,id)).isEqualTo(1);
        assertThat(balance(setup.customer())).isEqualTo(1000);
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void competingAdminEditsNeverSilentlyOverwrite() throws Exception {
        var admin=login(RoleCode.ADMIN);assume(admin,RoleCode.ADMIN);var p=catalogue.create(request("Concurrent "+UUID.randomUUID()),UUID.randomUUID());
        try(var blocker=jdbc.getDataSource().getConnection();var statement=blocker.prepareStatement("SELECT id FROM loyalty.prizes WHERE id=? FOR UPDATE");var pool=Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);statement.setObject(1,p.id());statement.executeQuery().close();
            Callable<Integer> call=()->mvc.perform(patch(ADMIN+"/"+p.id()).cookie(admin.cookies.values().toArray(jakarta.servlet.http.Cookie[]::new))
                    .header("X-CSRF-TOKEN",admin.csrf).contentType("application/json").content("{\"expectedVersion\":0,\"name\":\"Writer "+UUID.randomUUID()+"\"}")).andReturn().getResponse().getStatus();
            var a=pool.submit(call);var b=pool.submit(call);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);boolean blocked=false;
            while(System.nanoTime()<deadline) {
                try(var clear=blocker.createStatement()) {clear.execute("SELECT pg_stat_clear_snapshot()");}
                long writers;
                try(var probe=blocker.createStatement();var rows=probe.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND lower(query) LIKE '%update%prizes%'")) {
                    rows.next();writers=rows.getLong(1);
                }
                if(writers>=2) {blocked=true;break;}Thread.sleep(10);
            }
            blocker.commit();assertThat(blocked).isTrue();assertThat(List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(catalogue.get(p.id()).version()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.audit_logs WHERE target_id=? AND action='PRIZE_UPDATED'",Long.class,p.id())).isEqualTo(1);
    }
}
