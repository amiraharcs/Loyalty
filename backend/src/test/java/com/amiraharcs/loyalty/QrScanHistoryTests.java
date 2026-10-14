package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.common.api.ApiErrorCode;
import com.amiraharcs.loyalty.qr.model.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class QrScanHistoryTests extends StaffQrTestSupport {
    QrScanEvent event(UUID requestId) {
        var found=history.byRequestId(requestId,NOW.minusSeconds(1),NOW.plusSeconds(300),100);
        assertThat(found).hasSize(1);return found.getFirst();
    }
    UUID requestId(org.springframework.test.web.servlet.MvcResult result) { return UUID.fromString(result.getResponse().getHeader("X-Request-ID")); }
    @Test void typedHistoryCorrelatesSuccessWithResolutionAndVisitAndNeverContainsSensitiveData(CapturedOutput output) throws Exception {
        var setup=setup();var scanResponse=scanHttp(setup.staff(),setup.token(),setup.store().getId(),201);
        var resolution=json.readValue(scanResponse.getResponse().getContentAsString(),com.amiraharcs.loyalty.qr.api.StaffScanResponse.class);
        var scanned=event(requestId(scanResponse));
        assertThat(scanned.outcome()).isEqualTo(ScanEventOutcome.RESOLUTION_CREATED);assertThat(scanned.reasonCode()).isNull();
        assertThat(scanned.staffUserId()).isEqualTo(setup.staff().userId);assertThat(scanned.storeId()).isEqualTo(setup.store().getId());
        assertThat(scanned.tokenJti()).isEqualTo(jti(setup.token()));assertThat(scanned.resolutionId()).isEqualTo(resolution.scanResolutionId());assertThat(scanned.visitId()).isNull();
        var confirmed=confirmHttp(setup.staff(),resolution.scanResolutionId(),UUID.randomUUID(),201);
        var visit=json.readValue(confirmed.getResponse().getContentAsString(),com.amiraharcs.loyalty.qr.api.StaffVisitResponse.class);
        var confirmation=event(requestId(confirmed));assertThat(confirmation.outcome()).isEqualTo(ScanEventOutcome.VISIT_CONFIRMED);
        assertThat(confirmation.visitId()).isEqualTo(visit.visitId());assertThat(confirmation.resolutionId()).isEqualTo(resolution.scanResolutionId());
        var byStaff=history.byStaff(setup.staff().userId,NOW.minusSeconds(1),NOW.plusSeconds(1),100);
        assertThat(byStaff).hasSize(2);assertThat(history.byStore(setup.store().getId(),NOW.minusSeconds(1),NOW.plusSeconds(1),100)).containsExactlyElementsOf(byStaff);
        assertThat(history.byToken(jti(setup.token()),NOW.minusSeconds(1),NOW.plusSeconds(1),100)).containsExactlyElementsOf(byStaff);
        assertThat(history.byOutcome(ScanEventOutcome.VISIT_CONFIRMED,NOW.minusSeconds(1),NOW.plusSeconds(1),100)).contains(confirmation);
        assertThat(byStaff).isSortedAccordingTo(Comparator.comparing(QrScanEvent::occurredAt).reversed().thenComparing(entry->entry.id().toString(),Comparator.reverseOrder()));
        assertThat(history.byStaff(setup.staff().userId,NOW,NOW.plusSeconds(1),1)).hasSize(1);
        assertThat(history.byStaff(setup.staff().userId,NOW.minusSeconds(1),NOW,100)).isEmpty();
        String stored=jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(e))::text FROM loyalty.qr_scan_events e WHERE staff_user_id=?",String.class,setup.staff().userId);
        assertThat(stored).doesNotContain(setup.token(),"@example.org",PASSWORD,"csrf","cookie","secret");
        assertThat(output.getAll()).doesNotContain(setup.token());awardedOnce(setup);
    }
    @ParameterizedTest @ValueSource(strings={"malformed","tampered","expired","revoked","unknownKey","unknownRecord"})
    void unsafeQrAttemptsKeepActorAndCorrelationWithoutTrustingClaims(String state,CapturedOutput output) throws Exception {
        var setup=setup();String raw=setup.token();String expected="QR_INVALID";
        switch(state){
            case "malformed"->raw="private-qr-not-a-valid-jws";
            case "tampered"->{var parts=raw.split("\\.");parts[2]=(parts[2].startsWith("A")?"B":"A")+parts[2].substring(1);raw=String.join(".",parts);}
            case "expired"->{when(clock.instant()).thenReturn(NOW.plusSeconds(60));expected="QR_EXPIRED";}
            case "revoked"->{issue(setup.customer());expected="QR_REVOKED";}
            case "unknownKey"->{var parts=raw.split("\\.");parts[0]=com.nimbusds.jose.util.Base64URL.encode("{\"alg\":\"HS256\",\"typ\":\"loyalty-qr+jwt\",\"kid\":\"unknown\"}").toString();raw=String.join(".",parts);}
            case "unknownRecord"->raw=codec.sign(nonce(),NOW);
        }
        var response=setup.staff().send(post("/api/v1/staff/scans").header("X-CSRF-TOKEN",setup.staff().csrf).header("X-Request-ID",raw)
                .contentType("application/json").content(json.writeValueAsString(Map.of("token",raw,"storeId",setup.store().getId()))),409);
        assertThat(code(response)).isEqualTo(expected);var rejected=event(requestId(response));
        assertThat(rejected.staffUserId()).isEqualTo(setup.staff().userId);assertThat(rejected.storeId()).isEqualTo(setup.store().getId());
        assertThat(rejected.outcome()).isEqualTo(ScanEventOutcome.REJECTED);assertThat(rejected.reasonCode()).isEqualTo(expected);
        assertThat(rejected.tokenJti()).isNull();assertThat(rejected.resolutionId()).isNull();assertThat(rejected.requestId().toString()).isNotEqualTo(raw);
        assertThat(rejected.toString()).doesNotContain(raw);assertThat(response.getResponse().getContentAsString()).doesNotContain(raw,"MACVerifier","signature","keyId");
        assertThat(output.getAll()).doesNotContain(raw);unchanged(customer(setup.customer()));
    }
    @ParameterizedTest @ValueSource(strings={"unassigned","inactive","missing"})
    void deniedStoreIdentifiersAreNeverRecordedAsValidatedScope(String state) throws Exception {
        var setup=setup();UUID store=setup.store().getId();
        switch(state){
            case "unassigned"->jdbc.update("UPDATE loyalty.staff_store SET active=false WHERE staff_user_id=? AND store_id=?",setup.staff().userId,store);
            case "inactive"->jdbc.update("UPDATE loyalty.stores SET status='INACTIVE' WHERE id=?",store);
            case "missing"->store=UUID.randomUUID();
        }
        var response=scanHttp(setup.staff(),setup.token(),store,404);var rejected=event(requestId(response));
        assertThat(rejected.staffUserId()).isEqualTo(setup.staff().userId);assertThat(rejected.storeId()).isNull();assertThat(rejected.tokenJti()).isNull();
        assertThat(rejected.reasonCode()).isEqualTo("RESOURCE_NOT_FOUND");unchanged(customer(setup.customer()));
    }
    @Test void anotherStaffCannotPolluteTheirHistoryWithForeignResolutionContext() throws Exception {
        var setup=setup();var resolution=scan(setup);var intruder=login(RoleCode.STAFF);assign(intruder,setup.store());
        var response=confirmHttp(intruder,resolution.scanResolutionId(),UUID.randomUUID(),404);var rejected=event(requestId(response));
        assertThat(rejected.staffUserId()).isEqualTo(intruder.userId);assertThat(rejected.tokenJti()).isNull();assertThat(rejected.storeId()).isNull();
        assertThat(rejected.resolutionId()).isNull();assertThat(rejected.visitId()).isNull();unchanged(customer(setup.customer()));
    }
    @ParameterizedTest @ValueSource(strings={"expired","consumed","duplicate"})
    void legitimateOwnedResolutionFailureRetainsOnlyItsValidatedContext(String state) throws Exception {
        var setup=setup();var resolution=scan(setup);var duplicate=state.equals("duplicate")?scan(setup):resolution;
        if(state.equals("expired")){when(clock.instant()).thenReturn(NOW.plusSeconds(120));}
        else{confirmHttp(setup.staff(),resolution.scanResolutionId(),UUID.randomUUID(),201);}
        var response=confirmHttp(setup.staff(),duplicate.scanResolutionId(),UUID.randomUUID(),409);var rejected=event(requestId(response));
        assertThat(rejected.reasonCode()).isEqualTo(switch(state){case "expired"->"SCAN_RESOLUTION_EXPIRED";case "consumed"->"SCAN_RESOLUTION_CONSUMED";default->"VISIT_ALREADY_RECORDED";});
        assertThat(rejected.tokenJti()).isEqualTo(jti(setup.token()));assertThat(rejected.resolutionId()).isEqualTo(duplicate.scanResolutionId());
        assertThat(rejected.storeId()).isEqualTo(setup.store().getId());assertThat(rejected.visitId()).isNull();
        if(state.equals("expired")){unchanged(customer(setup.customer()));}else{awardedOnce(setup);}
    }
    @Test void ledgerInsertFailureRollsBackVisitEarnAndSuccessHistoryButKeepsIndependentRejection() throws Exception {
        var setup=setup();var resolution=scan(setup);var key=UUID.randomUUID();
        doAnswer(invocation->{throw new IllegalStateException("synthetic private ledger failure");})
                .when(org.springframework.test.util.AopTestUtils.<com.amiraharcs.loyalty.loyalty.repository.PointTransactionRepository>getUltimateTargetObject(pointTransactions)).append(any());
        org.springframework.test.web.servlet.MvcResult response;
        try{response=confirmHttp(setup.staff(),resolution.scanResolutionId(),key,500);}finally{reset(pointTransactions);}
        assertThat(event(requestId(response)).reasonCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(history.byStaff(setup.staff().userId,NOW.minusSeconds(1),NOW.plusSeconds(1),100))
                .extracting(QrScanEvent::outcome).containsExactlyInAnyOrder(ScanEventOutcome.RESOLUTION_CREATED,ScanEventOutcome.REJECTED);
        unchanged(customer(setup.customer()));assertThat(validator.validate(setup.token())).isNotNull();
        confirmHttp(setup.staff(),resolution.scanResolutionId(),key,201);awardedOnce(setup);
    }
    @Test void concurrentReplayRecordsOneSuccessAndOneRejectedAttempt() throws Exception {
        var setup=setup();var resolution=scan(setup);var barrier=new CyclicBarrier(2);
        try(var executor=Executors.newFixedThreadPool(2)){
            Callable<String> attempt=()->{assume(setup.staff(),RoleCode.STAFF);try{barrier.await(10,TimeUnit.SECONDS);
                flow.confirm(new com.amiraharcs.loyalty.qr.api.VisitConfirmationRequest(resolution.scanResolutionId()),UUID.randomUUID(),UUID.randomUUID());return "success";}
                catch(com.amiraharcs.loyalty.common.api.ConflictException rejected){return rejected.code().name();}
                finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}};
            var first=executor.submit(attempt);var second=executor.submit(attempt);
            assertThat(List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder("success","SCAN_RESOLUTION_CONSUMED");
        }
        var recorded=history.byStaff(setup.staff().userId,NOW.minusSeconds(1),NOW.plusSeconds(1),100);
        assertThat(recorded).extracting(QrScanEvent::outcome).containsExactlyInAnyOrder(ScanEventOutcome.RESOLUTION_CREATED,ScanEventOutcome.VISIT_CONFIRMED,ScanEventOutcome.REJECTED);
        awardedOnce(setup);
    }
    @Test void unavailableRejectionWriterPreservesOriginalSafeFailureWithoutRecursiveWrites(CapturedOutput output) throws Exception {
        var setup=setup();String secret="synthetic signing-secret and SQL detail";
        doThrow(new IllegalStateException(secret))
                .when(org.springframework.test.util.AopTestUtils.<com.amiraharcs.loyalty.qr.service.ScanEventService>getUltimateTargetObject(events))
                .failure(eq(setup.staff().userId),any(),any(com.amiraharcs.loyalty.common.api.ApiErrorCode.class),any());
        org.springframework.test.web.servlet.MvcResult response;
        try{response=scanHttp(setup.staff(),"private-invalid-encoded-input",setup.store().getId(),409);}finally{reset(events);}
        assertThat(code(response)).isEqualTo("QR_INVALID");assertThat(response.getResponse().getContentAsString()).doesNotContain(secret);
        assertThat(output.getAll()).doesNotContain(secret,"private-invalid-encoded-input");
        assertThat(history.byRequestId(requestId(response),NOW.minusSeconds(1),NOW.plusSeconds(1),10)).isEmpty();unchanged(customer(setup.customer()));
    }
    @Test void boundedQuerySupportRejectsOversizedRangesLimitsAndEncodedTokens() throws Exception {
        var setup=setup();
        for(int limit:List.of(0,101)){
            assertThatThrownBy(()->history.byStaff(setup.staff().userId,NOW,NOW.plusSeconds(1),limit)).isInstanceOfAny(IllegalArgumentException.class,org.springframework.dao.InvalidDataAccessApiUsageException.class);
        }
        assertThatThrownBy(()->history.byStaff(setup.staff().userId,NOW,NOW.plus(Duration.ofDays(32)),10)).isInstanceOfAny(IllegalArgumentException.class,org.springframework.dao.InvalidDataAccessApiUsageException.class);
        assertThatThrownBy(()->history.byToken(setup.token(),NOW,NOW.plusSeconds(1),10)).isInstanceOfAny(IllegalArgumentException.class,org.springframework.dao.InvalidDataAccessApiUsageException.class);
        assertThatThrownBy(()->history.byStaff(setup.staff().userId,NOW,NOW,10)).isInstanceOfAny(IllegalArgumentException.class,org.springframework.dao.InvalidDataAccessApiUsageException.class);
    }
    @Test void newCorrelationForeignKeysAndExistingAppendOnlyGuardsRemainEffective() throws Exception {
        var setup=setup();scan(setup);
        for(String sql:List.of("UPDATE loyalty.qr_scan_events SET id=id","DELETE FROM loyalty.qr_scan_events WHERE false","TRUNCATE loyalty.qr_scan_events")){
            assertThatThrownBy(()->jdbc.execute(sql)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
        assertThatThrownBy(()->jdbc.update("INSERT INTO loyalty.qr_scan_events(id,staff_user_id,occurred_at,outcome,reason_code,request_id,resolution_id) VALUES (?,?,now(),'REJECTED','QR_INVALID',?,?)",
                UUID.randomUUID(),setup.staff().userId,UUID.randomUUID(),UUID.randomUUID())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
