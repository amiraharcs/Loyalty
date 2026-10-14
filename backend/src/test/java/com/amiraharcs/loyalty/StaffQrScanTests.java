package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.qr.api.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.boot.test.system.CapturedOutput;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class StaffQrScanTests extends StaffQrTestSupport {
    @Test void assignedStaffResolvesOnlyLimitedProjectionWithoutAwardingPoints(CapturedOutput output) throws Exception {
        var setup=setup();var result=scanHttp(setup.staff(),setup.token(),setup.store().getId(),201);
        var dto=json.readValue(result.getResponse().getContentAsString(),StaffScanResponse.class);
        assertThat(json.readTree(result.getResponse().getContentAsString()).size()).isEqualTo(4);
        assertThat(dto.expiresAt()).isEqualTo(NOW.plusSeconds(120));assertThat(dto.balance()).isZero();
        assertThat(dto.displayName()).isEqualTo("Private synthetic member");
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("email","customerId","userId","password","streak","roles",setup.token());
        var row=jdbc.queryForMap("SELECT * FROM loyalty.scan_resolutions WHERE id=?",dto.scanResolutionId());
        assertThat(row).containsEntry("token_jti",jti(setup.token())).containsEntry("customer_id",customer(setup.customer()))
                .containsEntry("staff_user_id",setup.staff().userId).containsEntry("store_id",setup.store().getId()).containsEntry("consumed_at",null);
        assertThat(row.toString()).doesNotContain(setup.token());
        var event=jdbc.queryForMap("SELECT * FROM loyalty.qr_scan_events WHERE request_id=?",UUID.fromString(result.getResponse().getHeader("X-Request-ID")));
        assertThat(event).containsEntry("outcome","RESOLUTION_CREATED").containsEntry("token_jti",jti(setup.token()));
        assertThat(event.toString()).doesNotContain(setup.token());assertThat(output.getAll()).doesNotContain(setup.token());
        unchanged(customer(setup.customer()));assertThat(validator.validate(setup.token())).isNotNull();
        assertThat(new StaffScanRequest(setup.token(),setup.store().getId()).toString()).isEqualTo("StaffScanRequest[redacted]");
    }
    @ParameterizedTest @CsvSource({"CUSTOMER,403","ADMIN,403","EMPTY,403","STAFF,201","STAFF|ADMIN,201","STAFF|CUSTOMER,201"})
    void scanAndConfirmRequireExplicitStaffRole(String grants,int expected) throws Exception {
        var roles=grants.equals("EMPTY")?new RoleCode[0]:Arrays.stream(grants.split("\\|")).map(RoleCode::valueOf).toArray(RoleCode[]::new);
        var browser=login(roles);var customer=login(RoleCode.CUSTOMER);var shop=store(browser);var token=issue(customer).token();
        scanHttp(browser,token,shop.getId(),expected);assume(browser,roles);
        if(expected==403){
            assertThatThrownBy(()->flow.scan(new StaffScanRequest(token,shop.getId()),UUID.randomUUID())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThatThrownBy(()->flow.confirm(new VisitConfirmationRequest(UUID.randomUUID()),UUID.randomUUID(),UUID.randomUUID())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
            assertThat(code(confirmHttp(browser,UUID.randomUUID(),UUID.randomUUID(),403))).isEqualTo("FORBIDDEN");
        } else { assertThat(flow.scan(new StaffScanRequest(token,shop.getId()),UUID.randomUUID())).isNotNull(); }
    }
    @Test void validCsrfAnonymousRequestsReturn401AndMissingCsrfIs403() throws Exception {
        var anonymous=new Browser();anonymous.csrf();var store=UUID.randomUUID();
        assertThat(code(scanHttp(anonymous,"malformed",store,401))).isEqualTo("UNAUTHENTICATED");
        assertThat(code(confirmHttp(anonymous,UUID.randomUUID(),UUID.randomUUID(),401))).isEqualTo("UNAUTHENTICATED");
        var setup=setup();setup.staff().send(post("/api/v1/staff/scans").contentType("application/json").content(json.writeValueAsString(Map.of("token",setup.token(),"storeId",setup.store().getId()))),403);
        setup.staff().send(post("/api/v1/staff/visits").header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{}"),403);
        unchanged(customer(setup.customer()));
    }
    @ParameterizedTest @ValueSource(strings={"unassigned","inactive","missing","disabled","removedRole"})
    void staffAndStoreMustStillBeEligible(String state) throws Exception {
        var setup=setup();UUID store=setup.store().getId();int status=404;
        switch(state){
            case "unassigned"->jdbc.update("UPDATE loyalty.staff_store SET active=false WHERE staff_user_id=? AND store_id=?",setup.staff().userId,store);
            case "inactive"->jdbc.update("UPDATE loyalty.stores SET status='INACTIVE' WHERE id=?",store);
            case "missing"->store=UUID.randomUUID();
            case "disabled"->{jdbc.update("UPDATE loyalty.users SET status='DISABLED' WHERE id=?",setup.staff().userId);setup.staff().csrf();status=401;}
            case "removedRole"->{jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=?",setup.staff().userId);status=403;}
        }
        var result=scanHttp(setup.staff(),setup.token(),store,status);String error=code(result);
        if(status==404){failureRecorded(UUID.fromString(result.getResponse().getHeader("X-Request-ID")),error);}
        unchanged(customer(setup.customer()));
    }
    @ParameterizedTest @ValueSource(strings={"malformed","tampered","expired","revoked","unknownKid","issuer","audience","unrecorded","none","HS384"})
    void rejectsInvalidQrWithoutLeakingRawInputAndPersistsSafeFailure(String mutation,CapturedOutput output) throws Exception {
        var setup=setup();String token=setup.token();String expected="QR_INVALID";
        switch(mutation){
            case "malformed"->token="synthetic-invalid-private-qr";
            case "tampered"->{var parts=token.split("\\.");parts[2]=(parts[2].startsWith("A")?"B":"A")+parts[2].substring(1);token=String.join(".",parts);}
            case "expired"->{when(clock.instant()).thenReturn(NOW.plusSeconds(60));expected="QR_EXPIRED";}
            case "revoked"->{issue(setup.customer());expected="QR_REVOKED";}
            case "none"->token=new PlainJWT(new JWTClaimsSet.Builder().jwtID(nonce()).build()).serialize();
            default->{
                String kid=mutation.equals("unknownKid")?"unknown":signing.activeKeyId();
                var claims=new JWTClaimsSet.Builder().issuer(mutation.equals("issuer")?"wrong":signing.issuer())
                        .audience(mutation.equals("audience")?"wrong":signing.audience()).issueTime(Date.from(NOW)).expirationTime(Date.from(NOW.plusSeconds(60)))
                        .jwtID(mutation.equals("unrecorded")?nonce():jti(setup.token())).build();
                var algorithm=mutation.equals("HS384")?JWSAlgorithm.HS384:JWSAlgorithm.HS256;
                var jwt=new SignedJWT(new JWSHeader.Builder(algorithm).keyID(kid).type(new JOSEObjectType("loyalty-qr+jwt")).build(),claims);
                byte[] key=mutation.equals("HS384")?new byte[64]:signing.signingKey().getEncoded();jwt.sign(new MACSigner(key));token=jwt.serialize();
            }
        }
        var result=scanHttp(setup.staff(),token,setup.store().getId(),409);assertThat(code(result)).isEqualTo(expected);
        failureRecorded(UUID.fromString(result.getResponse().getHeader("X-Request-ID")),expected);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.scan_resolutions WHERE customer_id=?",Long.class,customer(setup.customer()))).isZero();
        assertThat(output.getAll()).doesNotContain(token);unchanged(customer(setup.customer()));
    }
    @ParameterizedTest @ValueSource(strings={"customerId","points","staffUserId","balance","expiry","email"})
    void allowlistedDtoRejectsInjectedFields(String field) throws Exception {
        var setup=setup();setup.staff().send(post("/api/v1/staff/scans").header("X-CSRF-TOKEN",setup.staff().csrf).contentType("application/json")
                .content(json.writeValueAsString(Map.of("token",setup.token(),"storeId",setup.store().getId(),field,"spoofed"))),400);
        unchanged(customer(setup.customer()));
    }
    @Test void selfServiceScanIsDeniedEvenWithBothRoles() throws Exception {
        var staff=login(RoleCode.CUSTOMER,RoleCode.STAFF);var shop=store(staff);var token=issue(staff).token();
        assertThat(code(scanHttp(staff,token,shop.getId(),403))).isEqualTo("FORBIDDEN");unchanged(customer(staff));
    }
    @Test void scanRateLimitReturnsRetryAfterAndDoesNotCreateAnotherResolution() throws Exception {
        var setup=setup();for(int i=0;i<60;i++){scan(setup);}
        var denied=scanHttp(setup.staff(),setup.token(),setup.store().getId(),429);
        assertThat(code(denied)).isEqualTo("RATE_LIMITED");assertThat(denied.getResponse().getHeader("Retry-After")).isEqualTo("60");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.scan_resolutions WHERE customer_id=?",Long.class,customer(setup.customer()))).isEqualTo(60);
        failureRecorded(UUID.fromString(denied.getResponse().getHeader("X-Request-ID")),"RATE_LIMITED");unchanged(customer(setup.customer()));
    }
}
