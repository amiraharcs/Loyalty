package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.api.RegistrationRequest;
import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.auth.security.*;
import com.amiraharcs.loyalty.auth.service.RegistrationService;
import com.amiraharcs.loyalty.common.api.RateLimitException;
import com.amiraharcs.loyalty.qr.api.QrTokenResponse;
import com.amiraharcs.loyalty.qr.service.*;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ExtendWith(OutputCaptureExtension.class)
class QrIdentityTokenTests {
    static final String PATH = "/api/v1/customers/me/qr-tokens";
    static final String PASSWORD = "a unique temporary identity phrase 752!";
    static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    static final AtomicInteger IPS = new AtomicInteger();
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired RegistrationService registration;
    @Autowired QrTokenService tokens;
    @Autowired QrTokenValidationService validator;
    @Autowired PlatformTransactionManager manager;
    @MockitoSpyBean QrTokenCodec codec;
    @MockitoSpyBean com.amiraharcs.loyalty.loyalty.service.LoyaltyAccountService accounts;
    @MockitoSpyBean Clock clock;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { PostgresTestDatabase.register(registry); }
    @BeforeEach void time() { when(clock.instant()).thenReturn(NOW); }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    class Browser {
        final Map<String,Cookie> cookies = new HashMap<>();
        final String ip = "192.0.2." + IPS.incrementAndGet();
        UUID userId;
        String csrf;
        MvcResult send(MockHttpServletRequestBuilder builder, int expected) throws Exception {
            if (!cookies.isEmpty()) { builder.cookie(cookies.values().toArray(Cookie[]::new)); }
            var result = mvc.perform(builder.with(request -> { request.setRemoteAddr(ip); return request; }))
                    .andExpect(status().is(expected)).andReturn();
            for (var cookie : result.getResponse().getCookies()) {
                if (cookie.getMaxAge()==0) { cookies.remove(cookie.getName()); }
                else { cookies.put(cookie.getName(),cookie); }
            }
            return result;
        }
        void csrf() throws Exception { csrf=json.readTree(send(get("/api/v1/auth/csrf"),200).getResponse().getContentAsString()).get("token").asText(); }
    }
    Browser login(RoleCode... roles) throws Exception {
        var browser=new Browser(); String email=UUID.randomUUID()+"@example.org";
        registration.register(new RegistrationRequest(email,PASSWORD,"Private synthetic member"));
        browser.userId=jdbc.queryForObject("SELECT id FROM loyalty.users WHERE canonical_email=?",UUID.class,email);
        jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=?",browser.userId);
        for(var role:roles) { jdbc.update("INSERT INTO loyalty.user_roles(user_id,role_code) VALUES (?,?)",browser.userId,role.name()); }
        browser.csrf(); browser.send(post("/api/v1/auth/sessions").header("X-CSRF-TOKEN",browser.csrf).contentType("application/json")
                .content(json.writeValueAsString(Map.of("email",email,"password",PASSWORD))),201);
        browser.csrf();return browser;
    }
    UUID customer(Browser browser) { return jdbc.queryForObject("SELECT id FROM loyalty.customer_profiles WHERE user_id=?",UUID.class,browser.userId); }
    void assume(Browser browser,RoleCode... roles) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new SessionPrincipal(browser.userId),null,RoleAuthorities.forRoles(List.of(roles))));
    }
    MvcResult request(Browser browser,int expected) throws Exception {
        var result=browser.send(post(PATH).header("X-CSRF-TOKEN",browser.csrf).header("X-Request-ID","untrusted"),expected);
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        if(expected>=400) {
            var error=json.readTree(result.getResponse().getContentAsString());
            assertThat(error.get("status").asInt()).isEqualTo(expected);
            assertThat(error.get("requestId").asText()).isEqualTo(result.getResponse().getHeader("X-Request-ID"));
            assertThat(error.get("requestId").asText()).isNotEqualTo("untrusted");
            assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
            assertThat(result.getResponse().getContentAsString()).doesNotContain("password","trace","signing-key","HmacSHA256","SQLException");
        }
        return result;
    }
    QrTokenResponse issue(Browser browser) throws Exception {
        return json.readValue(request(browser,201).getResponse().getContentAsString(),QrTokenResponse.class);
    }
    long rows(UUID customer,String condition) {
        return jdbc.queryForObject("SELECT count(*) FROM loyalty.qr_identity_tokens WHERE customer_id=? " + condition,Long.class,customer);
    }
    String jti(String encoded) throws Exception { return SignedJWT.parse(encoded).getJWTClaimsSet().getJWTID(); }
    String nonce() { byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }

    @Test void actualCustomerCookieAndCsrfIssueMinimalIdentityTokenAndPersistOnlyMetadata(CapturedOutput output) throws Exception {
        var browser=login(RoleCode.CUSTOMER);var customer=customer(browser);var result=request(browser,201);
        var body=json.readTree(result.getResponse().getContentAsString());assertThat(body.size()).isEqualTo(3);
        var response=json.readValue(result.getResponse().getContentAsString(),QrTokenResponse.class);
        assertThat(response.serverTime()).isEqualTo(NOW);assertThat(response.expiresAt()).isEqualTo(NOW.plusSeconds(60));
        var jwt=SignedJWT.parse(response.token());var claims=jwt.getJWTClaimsSet();
        assertThat(claims.getClaims().keySet()).containsExactlyInAnyOrder("iss","aud","iat","exp","jti");
        assertThat(claims.getIssuer()).isEqualTo("loyalty-test");assertThat(claims.getAudience()).containsExactly("loyalty-test-identity");
        assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("HS256");assertThat(claims.getJWTID()).hasSize(43);
        assertThat(jwt.getPayload().toString()).doesNotContain(customer.toString(),browser.userId.toString(),"example.org","Private synthetic","points","balance","roles");
        var identity=validator.validate(response.token());assertThat(identity.customerId()).isEqualTo(customer);
        var row=jdbc.queryForMap("SELECT * FROM loyalty.qr_identity_tokens WHERE jti=?",claims.getJWTID());
        assertThat(row.keySet()).containsExactlyInAnyOrder("jti","customer_id","issued_at","expires_at","key_id","status","revoked_at");
        assertThat(row).containsEntry("customer_id",customer).containsEntry("key_id","test-key").containsEntry("status","ACTIVE").containsEntry("revoked_at",null);
        assertThat(row.values().toString()).doesNotContain(response.token());
        assertThat(output.getAll()).doesNotContain(response.token());assertThat(response.toString()).isEqualTo("QrTokenResponse[redacted]");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.point_transactions WHERE account_id IN (SELECT id FROM loyalty.loyalty_accounts WHERE customer_id=?)",Long.class,customer)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.visits WHERE customer_id=?",Long.class,customer)).isZero();
    }
    @ParameterizedTest @CsvSource({"CUSTOMER,201","STAFF,403","ADMIN,403","EMPTY,403","CUSTOMER|STAFF,201","CUSTOMER|ADMIN,201"})
    void routeAndServiceRequireExplicitCustomer(String grants,int expected) throws Exception {
        var roles=grants.equals("EMPTY")?new RoleCode[0]:Arrays.stream(grants.split("\\|")).map(RoleCode::valueOf).toArray(RoleCode[]::new);
        var browser=login(roles);request(browser,expected);assume(browser,roles);
        if(expected==201){assertThat(tokens.issue()).isNotNull();}
        else {assertThatThrownBy(tokens::issue).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);assertThat(rows(customer(browser),"")).isZero();}
    }
    @Test void anonymousAndMissingCsrfAreDeniedWithNoPersistence() throws Exception {
        var anonymous=new Browser();anonymous.csrf();request(anonymous,401);
        var browser=login(RoleCode.CUSTOMER);browser.send(post(PATH),403);browser.csrf="incorrect";request(browser,403);
        assertThat(rows(customer(browser),"")).isZero();SecurityContextHolder.clearContext();
        assertThatThrownBy(tokens::issue).isInstanceOf(org.springframework.security.authentication.AuthenticationCredentialsNotFoundException.class);
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"customerId\":\"spoof\"}","{\"expiry\":99999}","{\"points\":9000}","{\"keyId\":\"spoof\"}","{\"email\":\"private@example.org\"}"," ","null"})
    void bodyCannotChooseClaimsIdentityOrExpiry(String body) throws Exception {
        var browser=login(RoleCode.CUSTOMER);
        browser.send(post(PATH).header("X-CSRF-TOKEN",browser.csrf).contentType("application/json").content(body),400);
        assertThat(rows(customer(browser),"")).isZero();
    }
    @Test void queryParametersCannotChooseIdentityEither() throws Exception {
        var browser=login(RoleCode.CUSTOMER);browser.send(post(PATH).header("X-CSRF-TOKEN",browser.csrf).param("customerId",UUID.randomUUID().toString()),400);
        assertThat(rows(customer(browser),"")).isZero();
    }
    @Test void missingProfileAndMissingLoyaltyAccountAreNotFound() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var customer=customer(browser);
        jdbc.update("DELETE FROM loyalty.loyalty_accounts WHERE customer_id=?",customer);request(browser,404);
        jdbc.update("DELETE FROM loyalty.streaks WHERE customer_id=?",customer);
        jdbc.update("DELETE FROM loyalty.game_life_state WHERE customer_id=?",customer);
        jdbc.update("DELETE FROM loyalty.customer_profiles WHERE id=?",customer);request(browser,404);
    }
    @Test void roleRemovalAndDisabledAccountRejectIssuanceAndValidation() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var token=issue(browser);assume(browser,RoleCode.CUSTOMER);
        jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=?",browser.userId);
        assertThatThrownBy(tokens::issue).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(()->validator.validate(token.token())).isInstanceOf(InvalidQrTokenException.class);
        SecurityContextHolder.clearContext();request(browser,403);
        jdbc.update("INSERT INTO loyalty.user_roles(user_id,role_code) VALUES (?,'CUSTOMER')",browser.userId);
        jdbc.update("UPDATE loyalty.users SET status='DISABLED' WHERE id=?",browser.userId);
        assume(browser,RoleCode.CUSTOMER);assertThatThrownBy(tokens::issue).isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class);
        assertThatThrownBy(()->validator.validate(token.token())).isInstanceOf(InvalidQrTokenException.class);
        SecurityContextHolder.clearContext();browser.csrf();request(browser,401);
        assertThat(rows(customer(browser),"")).isEqualTo(1);
    }
    @Test void replacementRevokesPriorTokenAndValidationResolvesOnlyTheNewOne() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var first=issue(browser);var second=issue(browser);
        assertThat(jti(first.token())).isNotEqualTo(jti(second.token()));
        assertThat(rows(customer(browser),"AND status='ACTIVE'")).isEqualTo(1);assertThat(rows(customer(browser),"AND status='REVOKED'")).isEqualTo(1);
        assertThatThrownBy(()->validator.validate(first.token())).isInstanceOfSatisfying(InvalidQrTokenException.class,ex->assertThat(ex.reason()).isEqualTo(InvalidQrTokenException.Reason.REVOKED));
        assertThat(validator.validate(second.token()).customerId()).isEqualTo(customer(browser));
    }
    @Test void expiryIsExclusiveAndFreshIssuanceRevokesExpiredActiveRecord() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var first=issue(browser);
        when(clock.instant()).thenReturn(NOW.plusSeconds(59));assertThat(validator.validate(first.token())).isNotNull();
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));assertThatThrownBy(()->validator.validate(first.token())).isInstanceOf(InvalidQrTokenException.class);
        var second=issue(browser);assertThat(second.serverTime()).isEqualTo(NOW.plusSeconds(60));
        assertThat(rows(customer(browser),"AND status='ACTIVE'")).isEqualTo(1);assertThat(rows(customer(browser),"AND status='REVOKED'")).isEqualTo(1);
    }
    @Test void consumedRevokedAndMissingRecordsCannotResolveIdentity() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var token=issue(browser);
        jdbc.update("UPDATE loyalty.qr_identity_tokens SET status='CONSUMED' WHERE jti=?",jti(token.token()));
        assertThatThrownBy(()->validator.validate(token.token())).isInstanceOf(InvalidQrTokenException.class);
        String unrecorded=codec.sign(nonce(),NOW);assertThatThrownBy(()->validator.validate(unrecorded)).isInstanceOf(InvalidQrTokenException.class);
    }
    @Test void signedClaimsMustMatchServerSideRecordMetadata() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var token=issue(browser);
        when(clock.instant()).thenReturn(NOW.plusSeconds(1));
        String differentTime=codec.sign(jti(token.token()),NOW.plusSeconds(1));
        assertThatThrownBy(()->validator.validate(differentTime)).isInstanceOf(InvalidQrTokenException.class);
    }
    @Test void thirtyPerMinuteIsEnforcedOnTheActualEndpointAndDirectService() throws Exception {
        var browser=login(RoleCode.CUSTOMER);
        for(int i=0;i<30;i++){issue(browser);}
        var result=request(browser,429);assertThat(result.getResponse().getHeader("Retry-After")).isEqualTo("60");
        assertThat(json.readTree(result.getResponse().getContentAsString()).get("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(rows(customer(browser),"")).isEqualTo(30);assertThat(rows(customer(browser),"AND status='ACTIVE'")).isEqualTo(1);
        assume(browser,RoleCode.CUSTOMER);assertThatThrownBy(tokens::issue).isInstanceOf(RateLimitException.class);SecurityContextHolder.clearContext();
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));issue(browser);
    }
    @Test void concurrentIssuanceLeavesExactlyOneCurrentToken() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var barrier=new CyclicBarrier(2);
        List<QrTokenResponse> results;
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<QrTokenResponse> task=()->{assume(browser,RoleCode.CUSTOMER);try{barrier.await(10,TimeUnit.SECONDS);return tokens.issue();}finally{SecurityContextHolder.clearContext();}};
            var first=executor.submit(task);var second=executor.submit(task);
            results=List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
        }
        assertThat(jti(results.getFirst().token())).isNotEqualTo(jti(results.getLast().token()));
        assertThat(rows(customer(browser),"AND status='ACTIVE'")).isEqualTo(1);assertThat(rows(customer(browser),"AND status='REVOKED'")).isEqualTo(1);
        int valid=0;for(var result:results){try{validator.validate(result.token());valid++;}catch(InvalidQrTokenException revoked){assertThat(revoked.reason()).isEqualTo(InvalidQrTokenException.Reason.REVOKED);}}
        assertThat(valid).isEqualTo(1);
    }
    @Test void signingFailureRollsBackBothReplacementAndNewTokenWithoutLoggingRawToken(CapturedOutput output) throws Exception {
        var browser=login(RoleCode.CUSTOMER);var first=issue(browser);
        doThrow(new IllegalStateException("Synthetic signing failure")).when(codec).sign(anyString(),any());
        try{request(browser,500);}finally{reset(codec);}
        assertThat(rows(customer(browser),"")).isEqualTo(1);assertThat(validator.validate(first.token())).isNotNull();
        assertThat(output.getAll()).doesNotContain(first.token());
    }
    @ParameterizedTest @ValueSource(strings={"jti","customerFk","liveUnique","expiry","fractionalTime","status","revokedAt","keyId"})
    void postgresEnforcesReferenceAndLifecycleConstraints(String invalid) throws Exception {
        var browser=login(RoleCode.CUSTOMER);var token=issue(browser);var other=login(RoleCode.CUSTOMER);
        String reference=invalid.equals("jti")?jti(token.token()):nonce();
        UUID owner=invalid.equals("customerFk")?UUID.randomUUID():invalid.equals("liveUnique")?customer(browser):customer(other);
        Instant issued=invalid.equals("fractionalTime")?NOW.plusMillis(1):NOW;
        Instant expiry=invalid.equals("expiry")?issued.plusSeconds(61):issued.plusSeconds(60);
        String status=invalid.equals("status")?"INVALID":invalid.equals("revokedAt")?"REVOKED":"ACTIVE";
        String keyId=invalid.equals("keyId")?"illegal key":"test-key";
        assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(tx->jdbc.update(
                "INSERT INTO loyalty.qr_identity_tokens(jti,customer_id,issued_at,expires_at,key_id,status) VALUES (?,?,?,?,?,?)",
                reference,owner,java.sql.Timestamp.from(issued),java.sql.Timestamp.from(expiry),keyId,status)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void postgresRejectsInfiniteLifetime() throws Exception {
        var browser=login(RoleCode.CUSTOMER);
        assertThatThrownBy(()->jdbc.update("INSERT INTO loyalty.qr_identity_tokens(jti,customer_id,issued_at,expires_at,key_id,status) VALUES (?,?,'infinity','infinity','test-key','ACTIVE')",nonce(),customer(browser)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class).hasMessageContaining("ck_qr_tokens_lifetime");
    }
    @Test void referencesPreventCustomerDeletionAndTerminalTokensCannotBeReactivatedOrRewritten() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var first=issue(browser);issue(browser);var customer=customer(browser);
        jdbc.update("DELETE FROM loyalty.loyalty_accounts WHERE customer_id=?",customer);
        assertThatThrownBy(()->jdbc.update("DELETE FROM loyalty.customer_profiles WHERE id=?",customer))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class).hasMessageContaining("fk_qr_tokens_customer");
        for(String sql:List.of("UPDATE loyalty.qr_identity_tokens SET status='ACTIVE',revoked_at=NULL WHERE jti=?",
                "UPDATE loyalty.qr_identity_tokens SET expires_at=expires_at+interval '1 second' WHERE jti=?",
                "UPDATE loyalty.qr_identity_tokens SET customer_id=customer_id WHERE jti=?")) {
            assertThatThrownBy(()->jdbc.update(sql,jti(first.token()))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
    }
    @Test void customerRoleRevokedDuringLockWaitPreventsReplacement() throws Exception {
        var browser=login(RoleCode.CUSTOMER);issue(browser);var customer=customer(browser);
        var beforeLock=new CountDownLatch(1);
        doAnswer(invocation->{beforeLock.countDown();return invocation.callRealMethod();})
                .when(org.springframework.test.util.AopTestUtils.<com.amiraharcs.loyalty.loyalty.service.LoyaltyAccountService>getUltimateTargetObject(accounts)).lockForMutation(eq(customer));
        // A held account lock forces issuance to recheck grants after the revoke commits.
        try(var executor=Executors.newSingleThreadExecutor()) {
            var future=new TransactionTemplate(manager).execute(tx->{
                jdbc.queryForObject("SELECT version FROM loyalty.loyalty_accounts WHERE customer_id=? FOR UPDATE",Long.class,customer);
                var pending=executor.submit(()->{assume(browser,RoleCode.CUSTOMER);try{return tokens.issue();}finally{SecurityContextHolder.clearContext();}});
                try{assertThat(beforeLock.await(10,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException ex){throw new IllegalStateException(ex);}
                jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=? AND role_code='CUSTOMER'",browser.userId);
                return pending;
            });
            assertThatThrownBy(()->future.get(20,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        }
        reset(accounts);
        assertThat(rows(customer,"")).isEqualTo(1);assertThat(rows(customer,"AND status='ACTIVE'")).isEqualTo(1);
    }

    @Test void scanRequiresAValidBodyAndInternalValidationRemainsUnexposed() throws Exception {
        var staff=login(RoleCode.STAFF);
        staff.send(post("/api/v1/staff/scans").header("X-CSRF-TOKEN",staff.csrf).contentType("application/json").content("{}"),400);
        staff.send(post("/api/v1/staff/qr-tokens/validate").header("X-CSRF-TOKEN",staff.csrf).contentType("application/json").content("{}"),404);
    }
}
