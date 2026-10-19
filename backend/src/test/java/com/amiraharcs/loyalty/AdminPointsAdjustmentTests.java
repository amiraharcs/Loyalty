package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.admin.api.*;
import com.amiraharcs.loyalty.admin.service.PointsAdjustmentService;
import com.amiraharcs.loyalty.audit.service.AuditService;
import com.amiraharcs.loyalty.auth.api.RegistrationRequest;
import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.auth.security.*;
import com.amiraharcs.loyalty.auth.service.RegistrationService;
import com.amiraharcs.loyalty.common.api.*;
import com.amiraharcs.loyalty.common.idempotency.IdempotencyService;
import com.amiraharcs.loyalty.loyalty.service.LoyaltyAccountService;
import jakarta.servlet.http.Cookie;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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

/** Actual cookie sessions/CSRF, PostgreSQL transactions, locks, migrations and append-only guards. */
@SpringBootTest
@AutoConfigureMockMvc
class AdminPointsAdjustmentTests {
    static final String PASSWORD = "a unique admin correction phrase 572!";
    static final AtomicInteger IPS = new AtomicInteger();
    static final long MAX = 9007199254740991L;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired RegistrationService registration;
    @Autowired PointsAdjustmentService adjustments;
    @MockitoSpyBean LoyaltyAccountService accounts;
    @Autowired PlatformTransactionManager manager;
    @MockitoSpyBean AuditService audit;
    @MockitoSpyBean IdempotencyService idempotency;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { PostgresTestDatabase.register(registry); }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    class Browser {
        final Map<String, Cookie> cookies = new HashMap<>();
        final String ip = "198.51.100." + IPS.incrementAndGet();
        UUID userId;
        String csrf;
        MvcResult send(MockHttpServletRequestBuilder builder, int expected) throws Exception {
            if (!cookies.isEmpty()) { builder.cookie(cookies.values().toArray(Cookie[]::new)); }
            var result = mvc.perform(builder.with(request -> { request.setRemoteAddr(ip); return request; }))
                    .andExpect(status().is(expected)).andReturn();
            for (var cookie : result.getResponse().getCookies()) {
                if (cookie.getMaxAge() == 0) { cookies.remove(cookie.getName()); }
                else { cookies.put(cookie.getName(), cookie); }
            }
            return result;
        }
        void csrf() throws Exception { csrf = json.readTree(send(get("/api/v1/auth/csrf"), 200).getResponse().getContentAsString()).get("token").asText(); }
    }
    UUID register() {
        String email = UUID.randomUUID() + "@example.org";
        registration.register(new RegistrationRequest(email, PASSWORD, "Synthetic customer"));
        return jdbc.queryForObject("SELECT id FROM loyalty.users WHERE canonical_email=?", UUID.class, email);
    }
    UUID profile(UUID userId) { return jdbc.queryForObject("SELECT id FROM loyalty.customer_profiles WHERE user_id=?", UUID.class, userId); }
    UUID account(UUID customerId) { return jdbc.queryForObject("SELECT id FROM loyalty.loyalty_accounts WHERE customer_id=?", UUID.class, customerId); }
    Browser login(RoleCode... roles) throws Exception {
        var browser = new Browser(); browser.userId = register();
        jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=?", browser.userId);
        for (var role : roles) { jdbc.update("INSERT INTO loyalty.user_roles(user_id,role_code) VALUES (?,?)", browser.userId, role.name()); }
        String email = jdbc.queryForObject("SELECT canonical_email FROM loyalty.users WHERE id=?", String.class, browser.userId);
        browser.csrf();
        browser.send(post("/api/v1/auth/sessions").header("X-CSRF-TOKEN", browser.csrf).contentType("application/json")
                .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))), 201);
        browser.csrf(); return browser;
    }
    void assume(UUID actorId, RoleCode... roles) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new SessionPrincipal(actorId), null, RoleAuthorities.forRoles(List.of(roles))));
    }
    PointsAdjustmentRequest body(long amount) { return new PointsAdjustmentRequest(BigInteger.valueOf(amount), "  Correct visit accounting  "); }
    PointsAdjustmentResponse adjust(UUID actor, UUID customer, long amount) {
        assume(actor, RoleCode.ADMIN); return adjustments.adjust(customer, body(amount), UUID.randomUUID(), UUID.randomUUID());
    }
    String path(UUID customer) { return "/api/v1/admin/customers/" + customer + "/points-adjustments"; }
    MvcResult request(Browser browser, UUID customer, String body, UUID key, int expected) throws Exception {
        var result = browser.send(post(path(customer)).header("X-CSRF-TOKEN", browser.csrf)
                .header("Idempotency-Key", key).header("X-Request-ID", "untrusted-client-id").contentType("application/json").content(body), expected);
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        if (expected >= 400) {
            var problem = json.readTree(result.getResponse().getContentAsString());
            assertThat(problem.get("status").asInt()).isEqualTo(expected);
            assertThat(problem.get("requestId").asText()).isEqualTo(result.getResponse().getHeader("X-Request-ID"));
            assertThat(problem.get("requestId").asText()).isNotEqualTo("untrusted-client-id");
            assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
            assertThat(result.getResponse().getContentAsString()).doesNotContain("password", "stackTrace", "SQLException", "constraint", "loyalty.point_transactions");
        }
        return result;
    }
    MvcResult request(Browser browser, UUID customer, long amount, int expected) throws Exception {
        return request(browser, customer, json.writeValueAsString(body(amount)), UUID.randomUUID(), expected);
    }
    long count(String table, UUID customer) {
        if (table.equals("audit_logs")) { return jdbc.queryForObject("SELECT count(*) FROM loyalty.audit_logs WHERE target_id=?", Long.class, customer); }
        return jdbc.queryForObject("SELECT count(*) FROM loyalty.point_transactions WHERE account_id=?", Long.class, account(customer));
    }

    @Test void positiveAndNegativeAppendSafeResponsesWithServerActorReasonTimeAndAtomicAudit() throws Exception {
        var admin = login(RoleCode.ADMIN); var customer = profile(register());
        var positive = request(admin, customer, 100, 201);
        var response = json.readTree(positive.getResponse().getContentAsString());
        assertThat(response.size()).isEqualTo(5);
        assertThat(response.get("newBalance").asLong()).isEqualTo(100);
        assertThat(response.get("signedAmount").asLong()).isEqualTo(100);
        assertThat(response.get("reason").asText()).isEqualTo("Correct visit accounting");
        var transactionId = UUID.fromString(response.get("transactionId").asText());
        var persisted = jdbc.queryForMap("SELECT type,actor_id,reason,visit_id,created_at FROM loyalty.point_transactions WHERE id=?", transactionId);
        assertThat(persisted).containsEntry("type", "ADJUSTMENT").containsEntry("actor_id", admin.userId)
                .containsEntry("reason", "Correct visit accounting").containsEntry("visit_id", null);
        var event = jdbc.queryForMap("SELECT * FROM loyalty.audit_logs WHERE target_id=?", customer);
        assertThat(event).containsEntry("actor_user_id", admin.userId).containsEntry("action", "ADMIN_POINTS_ADJUSTMENT")
                .containsEntry("target_type", "CUSTOMER").containsEntry("request_id", UUID.fromString(positive.getResponse().getHeader("X-Request-ID")));
        assertThat(event.get("metadata").toString()).contains(transactionId.toString()).doesNotContain("reason", "email", "password");
        assertThat(event.get("occurred_at")).isEqualTo(persisted.get("created_at"));
        var negative = json.readTree(request(admin, customer, -40, 201).getResponse().getContentAsString());
        assertThat(negative.get("newBalance").asLong()).isEqualTo(60);
        assertThat(count("point_transactions", customer)).isEqualTo(2);
        assertThat(count("audit_logs", customer)).isEqualTo(2);
        assertThat(accounts.history(customer, 0, 10).getContent()).extracting(entry -> entry.signedAmount()).containsExactly(-40L, 100L);
    }

    @ParameterizedTest @ValueSource(strings={"{}", "{\"signedAmount\":0,\"reason\":\"fix\"}", "{\"signedAmount\":1,\"reason\":\"   \"}",
        "{\"signedAmount\":9007199254740992,\"reason\":\"fix\"}", "{\"signedAmount\":-9007199254740992,\"reason\":\"fix\"}",
        "{\"signedAmount\":1.5,\"reason\":\"fix\"}", "{\"signedAmount\":\"1\",\"reason\":\"fix\"}",
        "{\"signedAmount\":true,\"reason\":\"fix\"}", "{\"signedAmount\":1,\"reason\":null}", "{"})
    void rejectsInvalidAmountsReasonsAndMalformedBodiesWithoutWrites(String body) throws Exception {
        var admin = login(RoleCode.ADMIN); var customer = profile(register());
        request(admin, customer, body, UUID.randomUUID(), 400);
        assertThat(count("point_transactions", customer)).isZero(); assertThat(count("audit_logs", customer)).isZero();
    }
    @Test void rejectsExcessiveReasonAndAcceptsExactlyOneThousandCharacters() throws Exception {
        var admin = login(RoleCode.ADMIN); var customer = profile(register());
        request(admin, customer, json.writeValueAsString(Map.of("signedAmount", 1, "reason", "x".repeat(1001))), UUID.randomUUID(), 400);
        request(admin, customer, json.writeValueAsString(Map.of("signedAmount", 1, "reason", "x".repeat(1000))), UUID.randomUUID(), 201);
    }
    @ParameterizedTest @ValueSource(strings={"actorId","accountId","transactionType","createdAt","newBalance","customerId"})
    void rejectsMassAssignment(String field) throws Exception {
        var admin = login(RoleCode.ADMIN); var customer = profile(register());
        request(admin, customer, json.writeValueAsString(Map.of("signedAmount", 1, "reason", "fix", field, "spoofed")), UUID.randomUUID(), 400);
        assertThat(count("point_transactions", customer)).isZero();
    }
    @ParameterizedTest @CsvSource({"CUSTOMER,403", "STAFF,403", "EMPTY,403", "ADMIN,201", "ADMIN|STAFF,201", "ADMIN|CUSTOMER,201"})
    void requiresExplicitAdminAtRouteAndService(String roleNames, int expected) throws Exception {
        var roles = roleNames.equals("EMPTY") ? new RoleCode[0] : Arrays.stream(roleNames.split("\\|")).map(RoleCode::valueOf).toArray(RoleCode[]::new);
        var browser = login(roles); var customer = profile(register()); request(browser, customer, 1, expected);
        assume(browser.userId, roles);
        if (expected == 403) { assertThatThrownBy(() -> adjustments.adjust(customer, body(1), UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class); }
        else { assertThat(adjustments.adjust(customer, body(1), UUID.randomUUID(), UUID.randomUUID()).newBalance()).isEqualTo(2); }
    }
    @Test void anonymousAndCsrfFailuresCannotMutate() throws Exception {
        var customer = profile(register()); var anonymous = new Browser(); anonymous.csrf(); request(anonymous, customer, 1, 401);
        var admin = login(RoleCode.ADMIN);
        admin.send(post(path(customer)).header("Idempotency-Key", UUID.randomUUID()).contentType("application/json")
                .content("{\"signedAmount\":1,\"reason\":\"fix\"}"), 403);
        admin.csrf = "invalid"; request(admin, customer, 1, 403);
        assertThat(count("point_transactions", customer)).isZero();
        SecurityContextHolder.clearContext(); assertThatThrownBy(() -> adjustments.adjust(customer, body(1), UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(org.springframework.security.authentication.AuthenticationCredentialsNotFoundException.class);
    }
    @Test void missingDisabledNonCustomerAndWrongIdTargetsAreSafeNotFound() throws Exception {
        var admin = login(RoleCode.ADMIN); var targetUser = register(); var customer = profile(targetUser);
        request(admin, UUID.randomUUID(), 1, 404); request(admin, targetUser, 1, 404); request(admin, account(customer), 1, 404);
        jdbc.update("UPDATE loyalty.users SET status='DISABLED' WHERE id=?", targetUser); request(admin, customer, 1, 404);
        jdbc.update("UPDATE loyalty.users SET status='ACTIVE' WHERE id=?", targetUser);
        jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=?", targetUser); request(admin, customer, 1, 404);
        assertThat(count("point_transactions", customer)).isZero();
    }
    @Test void serviceAndHttpRecheckRemovedAdminAndDisabledActor() throws Exception {
        var admin = login(RoleCode.ADMIN); var customer = profile(register()); assume(admin.userId, RoleCode.ADMIN);
        jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=?", admin.userId);
        assertThatThrownBy(() -> adjustments.adjust(customer, body(1), UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        SecurityContextHolder.clearContext(); request(admin, customer, 1, 403);
        jdbc.update("INSERT INTO loyalty.user_roles(user_id,role_code) VALUES (?,'ADMIN')", admin.userId);
        jdbc.update("UPDATE loyalty.users SET status='DISABLED' WHERE id=?", admin.userId);
        assume(admin.userId, RoleCode.ADMIN);
        assertThatThrownBy(() -> adjustments.adjust(customer, body(1), UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class);
        SecurityContextHolder.clearContext(); admin.csrf(); request(admin, customer, 1, 401);
    }
    @Test void insufficientPointsAndUnsafeTotalReturnStableConflicts() throws Exception {
        var admin = login(RoleCode.ADMIN); var customer = profile(register());
        var problem = json.readTree(request(admin, customer, -1, 409).getResponse().getContentAsString());
        assertThat(problem.get("code").asText()).isEqualTo("INSUFFICIENT_POINTS");
        request(admin, customer, MAX, 201);
        assertThat(json.readTree(request(admin, customer, 1, 409).getResponse().getContentAsString()).get("code").asText()).isEqualTo("CONFLICT");
        request(admin, customer, -MAX, 201); assertThat(accounts.currentBalance(customer)).isZero();
        assertThat(count("point_transactions", customer)).isEqualTo(2);
    }
    @Test void idempotentRetryReturnsOriginalSnapshotAndRejectsChangedPayloadOrTarget() throws Exception {
        var admin = login(RoleCode.ADMIN); var customer = profile(register()); var key = UUID.randomUUID();
        String payload = json.writeValueAsString(body(100));
        var original = request(admin, customer, payload, key, 201).getResponse().getContentAsString();
        request(admin, customer, -20, 201);
        assertThat(request(admin, customer, payload, key, 201).getResponse().getContentAsString()).isEqualTo(original);
        var changed = request(admin, customer, json.writeValueAsString(body(99)), key, 409);
        assertThat(json.readTree(changed.getResponse().getContentAsString()).get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        request(admin, profile(register()), payload, key, 409);
        assertThat(accounts.currentBalance(customer)).isEqualTo(80); assertThat(count("audit_logs", customer)).isEqualTo(2);
        jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=?", admin.userId);
        request(admin, customer, payload, key, 403);
    }
    @Test void requiresUuidIdempotencyHeader() throws Exception {
        var admin = login(RoleCode.ADMIN); var customer = profile(register());
        for (String key : List.of("", "not-a-uuid")) {
            var builder = post(path(customer)).header("X-CSRF-TOKEN", admin.csrf).contentType("application/json").content(json.writeValueAsString(body(1)));
            if (!key.isEmpty()) { builder.header("Idempotency-Key", key); }
            admin.send(builder, 400);
        }
        assertThat(count("point_transactions", customer)).isZero();
    }
    @Test void auditFailureRollsBackLedgerAuditAndRetryRecord() throws Exception {
        var admin = login(RoleCode.ADMIN); var customer = profile(register());
        doAnswer(invocation -> { invocation.callRealMethod(); throw new IllegalStateException("synthetic internal SQL secret"); })
                .when(org.springframework.test.util.AopTestUtils.<AuditService>getUltimateTargetObject(audit)).append(eq(admin.userId), anyString(), anyString(), eq(customer), any(), any(), anyString());
        try { request(admin, customer, 100, 500); }
        finally { reset(audit); }
        assertThat(count("point_transactions", customer)).isZero(); assertThat(count("audit_logs", customer)).isZero();
        assertThat(accounts.currentBalance(customer)).isZero(); request(admin, customer, 100, 201);
    }
    @Test void retryRecordFailureRollsBackTheWholeOperation() throws Exception {
        var admin = login(RoleCode.ADMIN); var customer = profile(register()); var key = UUID.randomUUID();
        doAnswer(invocation -> { invocation.callRealMethod(); throw new IllegalStateException("synthetic failure"); })
                .when(org.springframework.test.util.AopTestUtils.<IdempotencyService>getUltimateTargetObject(idempotency)).complete(eq(admin.userId), anyString(), eq(key), anyString(), anyString(), any());
        try { request(admin, customer, json.writeValueAsString(body(100)), key, 500); }
        finally { reset(idempotency); }
        assertThat(count("point_transactions", customer)).isZero(); assertThat(count("audit_logs", customer)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.idempotency_records WHERE actor_id=? AND key=?", Long.class, admin.userId, key)).isZero();
        request(admin, customer, json.writeValueAsString(body(100)), key, 201);
    }
    @Test void concurrentNegativeAdjustmentsSerializeAndNeverOverspend() throws Exception {
        var admin = login(RoleCode.ADMIN); var customer = profile(register()); adjust(admin.userId, customer, 100);
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<String> task = () -> {
                assume(admin.userId, RoleCode.ADMIN);
                try { barrier.await(10, TimeUnit.SECONDS); adjustments.adjust(customer, body(-100), UUID.randomUUID(), UUID.randomUUID()); return "success"; }
                catch (InsufficientPointsException expected) { return "insufficient"; }
                finally { SecurityContextHolder.clearContext(); }
            };
            var first = executor.submit(task); var second = executor.submit(task);
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder("success", "insufficient");
        }
        assertThat(accounts.currentBalance(customer)).isZero(); assertThat(count("point_transactions", customer)).isEqualTo(2);
        assertThat(count("audit_logs", customer)).isEqualTo(2);
    }
    @Test void concurrentSameKeyCreatesOneLedgerAndOneAudit() throws Exception {
        var admin = login(RoleCode.ADMIN); var customer = profile(register()); var key = UUID.randomUUID(); var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<PointsAdjustmentResponse> task = () -> {
                assume(admin.userId, RoleCode.ADMIN);
                try { barrier.await(10, TimeUnit.SECONDS); return adjustments.adjust(customer, body(100), key, UUID.randomUUID()); }
                finally { SecurityContextHolder.clearContext(); }
            };
            var first = executor.submit(task); var second = executor.submit(task);
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(second.get(20, TimeUnit.SECONDS));
        }
        assertThat(accounts.currentBalance(customer)).isEqualTo(100); assertThat(count("audit_logs", customer)).isEqualTo(1);
    }
    @Test void adminRevokedWhileWaitingForAccountLockCannotCommit() throws Exception {
        var admin = login(RoleCode.ADMIN); var customer = profile(register());
        var beforeLock = new CountDownLatch(1);
        doAnswer(invocation -> { beforeLock.countDown(); return invocation.callRealMethod(); })
                .when(org.springframework.test.util.AopTestUtils.<LoyaltyAccountService>getUltimateTargetObject(accounts)).lockForMutation(eq(customer));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = new TransactionTemplate(manager).execute(status -> {
                jdbc.queryForObject("SELECT version FROM loyalty.loyalty_accounts WHERE customer_id=? FOR UPDATE", Long.class, customer);
                var pending = executor.submit(() -> {
                    assume(admin.userId, RoleCode.ADMIN);
                    try { return adjustments.adjust(customer, body(100), UUID.randomUUID(), UUID.randomUUID()); }
                    finally { SecurityContextHolder.clearContext(); }
                });
                try { assertThat(beforeLock.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
                jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id=? AND role_code='ADMIN'", admin.userId);
                return pending;
            });
            assertThatThrownBy(() -> future.get(20, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        } finally { reset(accounts); }
        assertThat(count("point_transactions", customer)).isZero(); assertThat(count("audit_logs", customer)).isZero();
    }
    @Test void missingAccountIsNotFoundAndIdempotencyKeysAreActorScoped() throws Exception {
        var admin = login(RoleCode.ADMIN); var second = login(RoleCode.ADMIN); var customer = profile(register());
        var emptyCustomer = profile(register()); jdbc.update("DELETE FROM loyalty.loyalty_accounts WHERE customer_id=?", emptyCustomer);
        request(admin, emptyCustomer, 1, 404);
        var key = UUID.randomUUID(); var payload = json.writeValueAsString(body(1));
        request(admin, customer, payload, key, 201); request(second, customer, payload, key, 201);
        assertThat(accounts.currentBalance(customer)).isEqualTo(2); assertThat(count("audit_logs", customer)).isEqualTo(2);
    }
    @Test void directServiceValidationCannotBypassAmountOrReasonConstraints() throws Exception {
        var admin = login(RoleCode.ADMIN); var customer = profile(register()); assume(admin.userId, RoleCode.ADMIN);
        for (var invalid : List.of(body(0), new PointsAdjustmentRequest(BigInteger.ONE, " "), body(MAX + 1))) {
            assertThatThrownBy(() -> adjustments.adjust(customer, invalid, UUID.randomUUID(), UUID.randomUUID()))
                    .isInstanceOf(jakarta.validation.ConstraintViolationException.class);
        }
        assertThat(count("point_transactions", customer)).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"UPDATE", "DELETE", "TRUNCATE"})
    void ledgerAndAuditRemainImmutable(String operation) throws Exception {
        var admin = login(RoleCode.ADMIN); var customer = profile(register()); adjust(admin.userId, customer, 10);
        for (String table : List.of("point_transactions", "audit_logs")) {
            String sql = switch (operation) {
                case "UPDATE" -> "UPDATE loyalty." + table + " SET id=id";
                case "DELETE" -> "DELETE FROM loyalty." + table + " WHERE false";
                // Include referencing tables so immutable-history triggers, rather than FK truncate refusal, are tested.
                default -> "TRUNCATE loyalty." + table + " CASCADE";
            };
            assertThatThrownBy(() -> jdbc.execute(sql)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
        assertThat(count("point_transactions", customer)).isEqualTo(1); assertThat(count("audit_logs", customer)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"actor", "reason", "blank", "zero", "visit", "foreignActor"})
    void databaseRejectsInvalidAdjustmentSourceOrActorReasonAmount(String invalid) throws Exception {
        var admin = login(RoleCode.ADMIN); var customer = profile(register());
        Object actor = invalid.equals("actor") ? null : invalid.equals("foreignActor") ? UUID.randomUUID() : admin.userId;
        Object reason = invalid.equals("reason") ? null : invalid.equals("blank") ? " \t\n " : "Synthetic correction";
        long amount = invalid.equals("zero") ? 0 : 1;
        Object visit = invalid.equals("visit") ? UUID.randomUUID() : null;
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(status -> jdbc.update(
                "INSERT INTO loyalty.point_transactions(id,account_id,signed_amount,type,actor_id,reason,created_at,visit_id) VALUES (?,?,?,'ADJUSTMENT',?,?,now(),?)",
                UUID.randomUUID(), account(customer), amount, actor, reason, visit)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(count("point_transactions", customer)).isZero();
    }
}
