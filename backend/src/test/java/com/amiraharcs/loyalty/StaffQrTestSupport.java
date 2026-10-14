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
abstract class StaffQrTestSupport {
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
    @Autowired StaffQrService flow;
    @Autowired com.amiraharcs.loyalty.store.repository.StoreRepository stores;
    @Autowired com.amiraharcs.loyalty.store.repository.StaffStoreRepository assignments;
    @Autowired com.amiraharcs.loyalty.auth.repository.UserRepository users;
    @MockitoSpyBean com.amiraharcs.loyalty.audit.service.AuditService audit;
    @MockitoSpyBean ScanEventService events;
    @MockitoSpyBean com.amiraharcs.loyalty.common.idempotency.IdempotencyService idempotency;
    @Autowired com.amiraharcs.loyalty.qr.config.QrSigningSettings signing;
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

    com.amiraharcs.loyalty.store.model.Store store(Browser staff) {
        var store=stores.save(new com.amiraharcs.loyalty.store.model.Store("Synthetic café","Synthetic address",com.amiraharcs.loyalty.store.model.StoreStatus.ACTIVE,NOW));
        assign(staff,store);return store;
    }
    void assign(Browser staff,com.amiraharcs.loyalty.store.model.Store store) {
        var user=users.findById(staff.userId).orElseThrow();assignments.save(new com.amiraharcs.loyalty.store.model.StaffStore(user,store,user,NOW));
    }
    Setup setup() throws Exception {
        var customer=login(RoleCode.CUSTOMER);var staff=login(RoleCode.STAFF);var shop=store(staff);
        return new Setup(customer,staff,shop,issue(customer).token());
    }
    record Setup(Browser customer,Browser staff,com.amiraharcs.loyalty.store.model.Store store,String token) { }
    MvcResult scanHttp(Browser staff,String token,UUID store,int expected) throws Exception {
        return staff.send(post("/api/v1/staff/scans").header("X-CSRF-TOKEN",staff.csrf).contentType("application/json")
                .content(json.writeValueAsString(Map.of("token",token,"storeId",store))),expected);
    }
    com.amiraharcs.loyalty.qr.api.StaffScanResponse scan(Setup setup) throws Exception {
        return json.readValue(scanHttp(setup.staff(),setup.token(),setup.store().getId(),201).getResponse().getContentAsString(),com.amiraharcs.loyalty.qr.api.StaffScanResponse.class);
    }
    MvcResult confirmHttp(Browser staff,UUID resolution,UUID key,int expected) throws Exception {
        return staff.send(post("/api/v1/staff/visits").header("X-CSRF-TOKEN",staff.csrf).header("Idempotency-Key",key)
                .contentType("application/json").content(json.writeValueAsString(Map.of("scanResolutionId",resolution))),expected);
    }
    String code(MvcResult result) throws Exception {
        var error=json.readTree(result.getResponse().getContentAsString());
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(error.get("requestId").asText()).isEqualTo(result.getResponse().getHeader("X-Request-ID"));
        assertThat(result.getResponse().getContentAsString()).doesNotContain("password","trace","SQL","HmacSHA256","synthetic-hash");
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        return error.get("code").asText();
    }
    void unchanged(UUID customer) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.visits WHERE customer_id=?",Long.class,customer)).isZero();
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(signed_amount),0) FROM loyalty.point_transactions WHERE account_id IN (SELECT id FROM loyalty.loyalty_accounts WHERE customer_id=?)",Long.class,customer)).isZero();
    }
    void awardedOnce(Setup setup) {
        var customer=customer(setup.customer());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.visits WHERE customer_id=?",Long.class,customer)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.point_transactions t JOIN loyalty.loyalty_accounts a ON a.id=t.account_id WHERE a.customer_id=? AND t.type='EARN'",Long.class,customer)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT SUM(signed_amount) FROM loyalty.point_transactions WHERE account_id IN (SELECT id FROM loyalty.loyalty_accounts WHERE customer_id=?)",Long.class,customer)).isEqualTo(100);
    }
    void failureRecorded(UUID requestId,String code) {
        var row=jdbc.queryForMap("SELECT outcome,reason_code FROM loyalty.qr_scan_events WHERE request_id=?",requestId);
        assertThat(row).containsEntry("outcome","REJECTED").containsEntry("reason_code",code);
    }
}
