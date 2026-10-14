package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.qr.api.*;
import com.amiraharcs.loyalty.qr.service.ScanEventService;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class StaffQrConfirmationTests extends StaffQrTestSupport {
    @Test void confirmationConsumesResolutionAndQrAndCreatesOneVisitEarnAuditAndRetryRecord() throws Exception {
        var setup=setup();var resolution=scan(setup);var key=UUID.randomUUID();
        var result=confirmHttp(setup.staff(),resolution.scanResolutionId(),key,201);
        var response=json.readValue(result.getResponse().getContentAsString(),StaffVisitResponse.class);
        assertThat(response.earnedPoints()).isEqualTo(100);assertThat(response.updatedBalance()).isEqualTo(100);
        assertThat(response.confirmedAt()).isEqualTo(NOW);assertThat(response.businessDate()).isEqualTo(java.time.LocalDate.parse("2026-10-14"));
        assertThat(json.readTree(result.getResponse().getContentAsString()).size()).isEqualTo(5);
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(jdbc.queryForObject("SELECT consumed_at FROM loyalty.scan_resolutions WHERE id=?",java.sql.Timestamp.class,resolution.scanResolutionId()).toInstant()).isEqualTo(NOW);
        assertThat(jdbc.queryForObject("SELECT status FROM loyalty.qr_identity_tokens WHERE jti=?",String.class,jti(setup.token()))).isEqualTo("CONSUMED");
        var entry=jdbc.queryForMap("SELECT type,signed_amount,actor_id,visit_id FROM loyalty.point_transactions WHERE visit_id=?",response.visitId());
        assertThat(entry).containsEntry("type","EARN").containsEntry("signed_amount",100L).containsEntry("actor_id",setup.staff().userId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.audit_logs WHERE target_id=? AND action='VISIT_CONFIRMED'",Long.class,response.visitId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.qr_scan_events WHERE request_id=? AND outcome='VISIT_CONFIRMED'",Long.class,UUID.fromString(result.getResponse().getHeader("X-Request-ID")))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.idempotency_records WHERE actor_id=? AND key=?",Long.class,setup.staff().userId,key)).isEqualTo(1);
        awardedOnce(setup);
    }
    @Test void naturalQrExpiryDoesNotExpireTrustedResolutionButExclusiveTwoMinuteDeadlineDoes() throws Exception {
        var setup=setup();var resolution=scan(setup);
        when(clock.instant()).thenReturn(NOW.plusSeconds(119));confirmHttp(setup.staff(),resolution.scanResolutionId(),UUID.randomUUID(),201);awardedOnce(setup);
        when(clock.instant()).thenReturn(NOW);var another=setup();var expired=scan(another);
        when(clock.instant()).thenReturn(NOW.plusSeconds(120));
        assertThat(code(confirmHttp(another.staff(),expired.scanResolutionId(),UUID.randomUUID(),409))).isEqualTo("SCAN_RESOLUTION_EXPIRED");
        unchanged(customer(another.customer()));
    }
    @ParameterizedTest @ValueSource(strings={"replacement","revoked"})
    void explicitTokenRevocationInvalidatesOutstandingResolution(String mode) throws Exception {
        var setup=setup();var resolution=scan(setup);
        if(mode.equals("replacement")){issue(setup.customer());}
        else{jdbc.update("UPDATE loyalty.qr_identity_tokens SET status='REVOKED',revoked_at=? WHERE jti=?",java.sql.Timestamp.from(NOW),jti(setup.token()));}
        assertThat(code(confirmHttp(setup.staff(),resolution.scanResolutionId(),UUID.randomUUID(),409))).isEqualTo("QR_REVOKED");
        unchanged(customer(setup.customer()));
    }
    @Test void consumedResolutionCannotBeReusedWithAnotherKeyButExactRetryReturnsOriginalResultEvenAfterExpiry() throws Exception {
        var setup=setup();var resolution=scan(setup);var key=UUID.randomUUID();
        String original=confirmHttp(setup.staff(),resolution.scanResolutionId(),key,201).getResponse().getContentAsString();
        var rejected=confirmHttp(setup.staff(),resolution.scanResolutionId(),UUID.randomUUID(),409);
        assertThat(code(rejected)).isEqualTo("SCAN_RESOLUTION_CONSUMED");failureRecorded(UUID.fromString(rejected.getResponse().getHeader("X-Request-ID")),"SCAN_RESOLUTION_CONSUMED");
        when(clock.instant()).thenReturn(NOW.plusSeconds(121));
        assertThat(confirmHttp(setup.staff(),resolution.scanResolutionId(),key,201).getResponse().getContentAsString()).isEqualTo(original);awardedOnce(setup);
        var changed=confirmHttp(setup.staff(),UUID.randomUUID(),key,409);assertThat(code(changed)).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }
    @Test void missingAndForeignStaffResolutionReturnTheSameNotFoundAndCannotReplayStoredResults() throws Exception {
        var setup=setup();var resolution=scan(setup);var other=login(RoleCode.STAFF);assign(other,setup.store());
        assertThat(code(confirmHttp(other,resolution.scanResolutionId(),UUID.randomUUID(),404))).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(code(confirmHttp(setup.staff(),UUID.randomUUID(),UUID.randomUUID(),404))).isEqualTo("RESOURCE_NOT_FOUND");
        var key=UUID.randomUUID();confirmHttp(setup.staff(),resolution.scanResolutionId(),key,201);
        assertThat(code(confirmHttp(other,resolution.scanResolutionId(),key,404))).isEqualTo("RESOURCE_NOT_FOUND");awardedOnce(setup);
    }
    @ParameterizedTest @ValueSource(strings={"assignment","store","customer","customerRole","staffRole","disabledStaff"})
    void stateChangesAfterScanBlockConfirmationAndStoredRetries(String state) throws Exception {
        var setup=setup();var resolution=scan(setup);int expected=404;
        switch(state){
            case "assignment"->jdbc.update("UPDATE loyalty.staff_store SET active=false WHERE staff_user_id=? AND store_id=?",setup.staff().userId,setup.store().getId());
            case "store"->jdbc.update("UPDATE loyalty.stores SET status='INACTIVE' WHERE id=?",setup.store().getId());
            case "customer"->jdbc.update("UPDATE loyalty.users SET status='DISABLED' WHERE id=?",setup.customer().userId);
            case "customerRole"->jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=?",setup.customer().userId);
            case "staffRole"->{jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=?",setup.staff().userId);expected=403;}
            case "disabledStaff"->{jdbc.update("UPDATE loyalty.users SET status='DISABLED' WHERE id=?",setup.staff().userId);setup.staff().csrf();expected=401;}
        }
        confirmHttp(setup.staff(),resolution.scanResolutionId(),UUID.randomUUID(),expected);unchanged(customer(setup.customer()));
        assertThat(jdbc.queryForObject("SELECT consumed_at FROM loyalty.scan_resolutions WHERE id=?",java.sql.Timestamp.class,resolution.scanResolutionId())).isNull();
    }
    @Test void completedIdempotentResultStillRequiresCurrentStoreAssignment() throws Exception {
        var setup=setup();var resolution=scan(setup);var key=UUID.randomUUID();confirmHttp(setup.staff(),resolution.scanResolutionId(),key,201);
        jdbc.update("UPDATE loyalty.staff_store SET active=false WHERE staff_user_id=? AND store_id=?",setup.staff().userId,setup.store().getId());
        confirmHttp(setup.staff(),resolution.scanResolutionId(),key,404);awardedOnce(setup);
    }
    @Test void duplicateDailyVisitThroughDifferentSourcesReturnsStableConflictWithoutConsumingSecondSource() throws Exception {
        var setup=setup();var first=scan(setup);var second=scan(setup);
        confirmHttp(setup.staff(),first.scanResolutionId(),UUID.randomUUID(),201);
        assertThat(code(confirmHttp(setup.staff(),second.scanResolutionId(),UUID.randomUUID(),409))).isEqualTo("VISIT_ALREADY_RECORDED");
        var fresh=issue(setup.customer());var third=json.readValue(scanHttp(setup.staff(),fresh.token(),setup.store().getId(),201).getResponse().getContentAsString(),StaffScanResponse.class);
        assertThat(code(confirmHttp(setup.staff(),third.scanResolutionId(),UUID.randomUUID(),409))).isEqualTo("VISIT_ALREADY_RECORDED");
        assertThat(validator.validate(fresh.token())).isNotNull();awardedOnce(setup);
    }
    @ParameterizedTest @ValueSource(strings={"customerId","storeId","points","earnedPoints","staffUserId","businessDate"})
    void confirmationOnlyAcceptsResolutionId(String field) throws Exception {
        var setup=setup();var resolution=scan(setup);
        setup.staff().send(post("/api/v1/staff/visits").header("X-CSRF-TOKEN",setup.staff().csrf).header("Idempotency-Key",UUID.randomUUID())
                .contentType("application/json").content(json.writeValueAsString(Map.of("scanResolutionId",resolution.scanResolutionId(),field,"spoofed"))),400);
        unchanged(customer(setup.customer()));
    }
    @Test void confirmationRequiresUuidIdempotencyHeader() throws Exception {
        var setup=setup();var resolution=scan(setup);
        for(String header:List.of("","invalid")){
            var builder=post("/api/v1/staff/visits").header("X-CSRF-TOKEN",setup.staff().csrf).contentType("application/json")
                    .content(json.writeValueAsString(Map.of("scanResolutionId",resolution.scanResolutionId())));
            if(!header.isEmpty()){builder.header("Idempotency-Key",header);}setup.staff().send(builder,400);
        }
        unchanged(customer(setup.customer()));
    }
    @ParameterizedTest @ValueSource(strings={"audit","event","idempotency"})
    void failureAfterVisitAndEarnRollsBackAllBusinessWritesAndPreservesRetryability(String failure) throws Exception {
        var setup=setup();var resolution=scan(setup);var key=UUID.randomUUID();
        switch(failure){
            case "audit"->doAnswer(call->{call.callRealMethod();throw new IllegalStateException("Synthetic failure");})
                .when(org.springframework.test.util.AopTestUtils.<com.amiraharcs.loyalty.audit.service.AuditService>getUltimateTargetObject(audit))
                .append(eq(setup.staff().userId),eq("VISIT_CONFIRMED"),anyString(),any(),any(),any(),anyString());
            case "event"->doAnswer(call->{call.callRealMethod();throw new IllegalStateException("Synthetic failure");})
                .when(org.springframework.test.util.AopTestUtils.<ScanEventService>getUltimateTargetObject(events))
                .success(anyString(),eq(setup.staff().userId),any(),eq("VISIT_CONFIRMED"),any());
            case "idempotency"->doAnswer(call->{call.callRealMethod();throw new IllegalStateException("Synthetic failure");})
                .when(org.springframework.test.util.AopTestUtils.<com.amiraharcs.loyalty.common.idempotency.IdempotencyService>getUltimateTargetObject(idempotency))
                .complete(eq(setup.staff().userId),anyString(),eq(key),anyString(),anyString(),any());
        }
        org.springframework.test.web.servlet.MvcResult rejected;
        try{rejected=confirmHttp(setup.staff(),resolution.scanResolutionId(),key,500);}finally{reset(audit,events,idempotency);}
        failureRecorded(UUID.fromString(rejected.getResponse().getHeader("X-Request-ID")),"INTERNAL_ERROR");
        unchanged(customer(setup.customer()));assertThat(validator.validate(setup.token())).isNotNull();
        assertThat(jdbc.queryForObject("SELECT consumed_at FROM loyalty.scan_resolutions WHERE id=?",java.sql.Timestamp.class,resolution.scanResolutionId())).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.audit_logs WHERE actor_user_id=?",Long.class,setup.staff().userId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.idempotency_records WHERE actor_id=? AND key=?",Long.class,setup.staff().userId,key)).isZero();
        confirmHttp(setup.staff(),resolution.scanResolutionId(),key,201);awardedOnce(setup);
    }
    @ParameterizedTest @ValueSource(strings={"sameResolution","differentStaffSameToken","differentResolutions","sameKey"})
    void racingConfirmationsProduceExactlyOneVisitAndEarn(String mode) throws Exception {
        var setup=setup();var first=scan(setup);var secondStaff=mode.equals("differentStaffSameToken")?login(RoleCode.STAFF):setup.staff();
        if(secondStaff!=setup.staff()){assign(secondStaff,setup.store());}
        var second=mode.equals("sameResolution")||mode.equals("sameKey")?first:
                json.readValue(scanHttp(secondStaff,setup.token(),setup.store().getId(),201).getResponse().getContentAsString(),StaffScanResponse.class);
        var barrier=new CyclicBarrier(2);var firstKey=UUID.randomUUID();var secondKey=mode.equals("sameKey")?firstKey:UUID.randomUUID();
        try(var executor=Executors.newFixedThreadPool(2)){
            Callable<String> a=()->parallel(setup.staff(),first.scanResolutionId(),firstKey,barrier);
            Callable<String> b=()->parallel(secondStaff,second.scanResolutionId(),secondKey,barrier);
            var left=executor.submit(a);var right=executor.submit(b);var outcomes=List.of(left.get(20,TimeUnit.SECONDS),right.get(20,TimeUnit.SECONDS));
            if(mode.equals("sameKey")){assertThat(outcomes).containsExactly("success","success");}
            else{assertThat(outcomes).containsExactlyInAnyOrder("success",mode.equals("sameResolution")?"SCAN_RESOLUTION_CONSUMED":"VISIT_ALREADY_RECORDED");}
        }
        awardedOnce(setup);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.audit_logs WHERE action='VISIT_CONFIRMED' AND target_id IN (SELECT id FROM loyalty.visits WHERE customer_id=?)",Long.class,customer(setup.customer()))).isEqualTo(1);
    }
    private String parallel(Browser staff,UUID id,UUID key,CyclicBarrier barrier) throws Exception {
        assume(staff,RoleCode.STAFF);try{barrier.await(10,TimeUnit.SECONDS);flow.confirm(new VisitConfirmationRequest(id),key,UUID.randomUUID());return "success";}
        catch(com.amiraharcs.loyalty.common.api.ConflictException conflict){return conflict.code().name();}
        finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }
}
