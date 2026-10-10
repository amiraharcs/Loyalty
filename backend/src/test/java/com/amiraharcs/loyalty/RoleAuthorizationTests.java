package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.api.RegistrationRequest;
import com.amiraharcs.loyalty.auth.api.SessionController;
import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.auth.security.RoleAuthorities;
import com.amiraharcs.loyalty.auth.service.RegistrationService;
import jakarta.servlet.http.Cookie;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real login, CSRF cookies, PostgreSQL sessions and production security; fixtures exist only in tests. */
@SpringBootTest(properties = "authorization.real-session-fixture=true")
@AutoConfigureMockMvc
@Import(RoleAuthorizationTests.Fixtures.class)
class RoleAuthorizationTests {
    private static final String CUSTOMER_PATH = "/api/v1/customers/me/_permission-fixture";
    private static final String STAFF_PATH = "/api/v1/staff/_permission-fixture";
    private static final String ADMIN_PATH = "/api/v1/admin/_permission-fixture";
    private static final String PASSWORD = "a unique café authorization phrase 937!";
    private static final AtomicInteger IPS = new AtomicInteger();
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired RegistrationService registration;
    @Autowired JdbcIndexedSessionRepository sessions;
    @Autowired FixtureService services;
    @Autowired FixtureController controllers;
    @Autowired SessionController authController;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) { PostgresTestDatabase.register(registry); }

    private class Browser {
        final Map<String, Cookie> cookies = new HashMap<>();
        final String ip = "198.51.100." + IPS.incrementAndGet();
        String csrf;
        UUID userId;
        MvcResult send(MockHttpServletRequestBuilder request, int expected) throws Exception {
            if (!cookies.isEmpty()) { request.cookie(cookies.values().toArray(Cookie[]::new)); }
            var result = mvc.perform(request.with(servlet -> { servlet.setRemoteAddr(ip); return servlet; }))
                    .andExpect(status().is(expected)).andReturn();
            for (var cookie : result.getResponse().getCookies()) {
                if (cookie.getMaxAge() == 0) { cookies.remove(cookie.getName()); }
                else { cookies.put(cookie.getName(), cookie); }
            }
            return result;
        }
        void csrf() throws Exception {
            var result = send(get("/api/v1/auth/csrf"), 200);
            csrf = json.readTree(result.getResponse().getContentAsString()).get("token").asText();
        }
        String sessionId() { return cookies.get("loyalty-session").getValue(); }
    }

    private Browser login(RoleCode... roles) throws Exception {
        String email = UUID.randomUUID() + "@example.org";
        registration.register(new RegistrationRequest(email, PASSWORD, "Haroon"));
        UUID id = jdbc.queryForObject("SELECT id FROM loyalty.users WHERE canonical_email = ?", UUID.class, email);
        jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id = ?", id);
        for (var role : roles) {
            jdbc.update("INSERT INTO loyalty.user_roles (user_id, role_code) VALUES (?, ?)", id, role.name());
        }
        var browser = new Browser(); browser.csrf();
        var result = browser.send(post("/api/v1/auth/sessions").header("X-CSRF-TOKEN", browser.csrf)
                .contentType("application/json").content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))), 201);
        browser.userId = UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("userId").asText());
        assertThat(browser.userId).isEqualTo(id);
        return browser;
    }

    private void route(Browser browser, String path, int expected) throws Exception {
        var result = browser.send(get(path), expected);
        if (expected == 200) {
            assertThat(json.readTree(result.getResponse().getContentAsString()).has("permission")).isTrue();
        } else {
            var error = json.readTree(result.getResponse().getContentAsString());
            assertThat(error.get("status").asInt()).isEqualTo(expected);
            assertThat(error.get("code").asText()).isEqualTo(expected == 401 ? "UNAUTHENTICATED" : "FORBIDDEN");
            assertThat(error.get("instance").asText()).isEqualTo(path);
            assertThat(UUID.fromString(error.get("requestId").asText())).isNotNull();
            assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
            assertThat(result.getResponse().getHeader("Location")).isNull();
            assertThat(result.getResponse().getContentAsString()).doesNotContain("trace", "password", "exception");
        }
    }

    @ParameterizedTest
    @CsvSource({
        "ANONYMOUS,401,401,401",
        "EMPTY,403,403,403",
        "CUSTOMER,200,403,403",
        "STAFF,403,200,403",
        "ADMIN,403,403,200",
        "CUSTOMER|STAFF,200,200,403",
        "STAFF|ADMIN,403,200,200",
        "CUSTOMER|ADMIN,200,403,200",
        "CUSTOMER|STAFF|ADMIN,200,200,200"
    })
    void explicitRoleMatrixIsNotHierarchical(String grants, int customer, int staff, int admin) throws Exception {
        Browser browser;
        if (grants.equals("ANONYMOUS")) { browser = new Browser(); }
        else {
            RoleCode[] roles = grants.equals("EMPTY") ? new RoleCode[0]
                    : Arrays.stream(grants.split("\\|")).map(RoleCode::valueOf).toArray(RoleCode[]::new);
            browser = login(roles);
            browser.send(get("/api/v1/auth/me"), 200);
        }
        route(browser, CUSTOMER_PATH, customer);
        route(browser, STAFF_PATH, staff);
        route(browser, ADMIN_PATH, admin);
    }

    @Test
    void roleRemovalTakesEffectOnTheNextRequestAndRotatesTheStoredSession() throws Exception {
        var browser = login(RoleCode.CUSTOMER, RoleCode.STAFF);
        route(browser, STAFF_PATH, 200);
        String oldId = browser.sessionId();
        jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id = ? AND role_code = 'STAFF'", browser.userId);
        route(browser, STAFF_PATH, 403);
        assertThat(browser.sessionId()).isNotEqualTo(oldId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.spring_session WHERE session_id = ?", Integer.class, oldId)).isZero();
        route(browser, CUSTOMER_PATH, 200);
        var me = browser.send(get("/api/v1/auth/me"), 200);
        assertThat(json.readTree(me.getResponse().getContentAsString()).get("roles").toString()).isEqualTo("[\"CUSTOMER\"]");
        mvc.perform(get(STAFF_PATH).cookie(new Cookie("loyalty-session", oldId))).andExpect(status().isUnauthorized());
    }

    @Test
    void newlyGrantedRolesBecomeAvailableWithoutLoggingInAgain() throws Exception {
        var browser = login(RoleCode.ADMIN);
        route(browser, STAFF_PATH, 403);
        jdbc.update("INSERT INTO loyalty.user_roles (user_id, role_code) VALUES (?, 'STAFF')", browser.userId);
        route(browser, STAFF_PATH, 200);
        route(browser, ADMIN_PATH, 200);
        route(browser, CUSTOMER_PATH, 403);
    }

    @Test
    void disabledAccountLosesEveryRoleProtectedRouteAndItsServerSession() throws Exception {
        var browser = login(RoleCode.CUSTOMER, RoleCode.STAFF, RoleCode.ADMIN);
        String id = browser.sessionId();
        jdbc.update("UPDATE loyalty.users SET status = 'DISABLED' WHERE id = ?", browser.userId);
        route(browser, ADMIN_PATH, 401);
        route(browser, STAFF_PATH, 401);
        route(browser, CUSTOMER_PATH, 401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.spring_session WHERE session_id = ?", Integer.class, id)).isZero();
    }

    @Test
    void allowedCustomerUrlCannotBypassTheStaffServiceOrAdminControllerCheck() throws Exception {
        var customer = login(RoleCode.CUSTOMER);
        route(customer, CUSTOMER_PATH + "/staff-method", 403);
        route(customer, CUSTOMER_PATH + "/admin-method", 403);
        var customerStaff = login(RoleCode.CUSTOMER, RoleCode.STAFF);
        route(customerStaff, CUSTOMER_PATH + "/staff-method", 200);
        var customerAdmin = login(RoleCode.CUSTOMER, RoleCode.ADMIN);
        route(customerAdmin, CUSTOMER_PATH + "/admin-method", 200);
    }

    @ParameterizedTest
    @CsvSource({
        "ANONYMOUS,401,401,401",
        "EMPTY,403,403,403",
        "CUSTOMER,200,403,403",
        "STAFF,403,200,403",
        "ADMIN,403,403,200",
        "CUSTOMER|STAFF,200,200,403",
        "STAFF|ADMIN,403,200,200",
        "CUSTOMER|ADMIN,200,403,200",
        "CUSTOMER|STAFF|ADMIN,200,200,200"
    })
    void directlyInvokedSpringBeansEnforceTheSameIndependentRoleMatrix(String grants,
            int customer, int staff, int admin) throws Exception {
        Browser browser = grants.equals("ANONYMOUS") ? null : login(grants.equals("EMPTY") ? new RoleCode[0]
                : Arrays.stream(grants.split("\\|")).map(RoleCode::valueOf).toArray(RoleCode[]::new));
        SecurityContext context = null;
        if (browser != null) {
            Session session = sessions.findById(browser.sessionId());
            context = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        }
        assertThat(AopUtils.isAopProxy(services)).isTrue();
        assertThat(AopUtils.isAopProxy(controllers)).isTrue();
        assertThat(AopUtils.isAopProxy(authController)).isTrue();
        try {
            if (context == null) { SecurityContextHolder.clearContext(); }
            else { SecurityContextHolder.setContext(context); }
            method(services::customer, customer);
            method(services::staff, staff);
            method(services::admin, admin);
            method(controllers::adminMethod, admin);
            if (browser == null) {
                assertThatThrownBy(() -> authController.me(null)).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
            } else {
                assertThat(authController.me(context.getAuthentication()).getBody().userId()).isEqualTo(browser.userId);
            }
        } finally { SecurityContextHolder.clearContext(); }
    }

    private static void method(java.util.function.Supplier<?> invocation, int expected) {
        if (expected == 200) { assertThat(invocation.get()).isNotNull(); }
        else {
            assertThatThrownBy(invocation::get).isInstanceOf(expected == 401
                    ? AuthenticationCredentialsNotFoundException.class : AccessDeniedException.class);
        }
    }

    @Test
    void databaseRoleRemovalAlsoRevokesMethodAccessWhenTheCustomerUrlRemainsAllowed() throws Exception {
        var browser = login(RoleCode.CUSTOMER, RoleCode.ADMIN);
        route(browser, CUSTOMER_PATH + "/admin-method", 200);
        jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id = ? AND role_code = 'ADMIN'", browser.userId);
        route(browser, CUSTOMER_PATH + "/admin-method", 403);
        route(browser, CUSTOMER_PATH, 200);
    }

    @Test
    void unknownAndUnmatchedPathsRemainProtectedForEveryExplicitRole() throws Exception {
        var anonymous = new Browser();
        route(anonymous, "/api/v1/unmatched", 401);
        var allRoles = login(RoleCode.CUSTOMER, RoleCode.STAFF, RoleCode.ADMIN);
        route(allRoles, "/api/v1/unmatched", 403);
        route(allRoles, "/api/v1/staff-extra", 403);
        route(allRoles, "/actuator/env", 403);
        // A matched role namespace with no handler is still protected, then safely returns 404.
        anonymous.send(get("/api/v1/admin/_missing-fixture"), 401);
        login(RoleCode.CUSTOMER).send(get("/api/v1/admin/_missing-fixture"), 403);
        allRoles.send(get("/api/v1/admin/_missing-fixture"), 404);
    }

    @Test
    void unsafeRoleProtectedRequestsStillRequireCsrfBeforeMethodExecution() throws Exception {
        var staff = login(RoleCode.STAFF);
        staff.send(post(STAFF_PATH), 403);
        staff.csrf();
        staff.send(post(STAFF_PATH).header("X-CSRF-TOKEN", staff.csrf), 200);
        var customer = login(RoleCode.CUSTOMER); customer.csrf();
        // Valid CSRF proves this is a role denial, not a token failure.
        customer.send(post(STAFF_PATH).header("X-CSRF-TOKEN", customer.csrf), 403);
    }

    @Test
    void permissionFailureDoesNotBreakCurrentUserCsrfOrIdempotentLogout() throws Exception {
        var browser = login(RoleCode.ADMIN);
        route(browser, STAFF_PATH, 403);
        browser.send(get("/api/v1/auth/me"), 200);
        browser.csrf();
        browser.send(delete("/api/v1/auth/sessions/current").header("X-CSRF-TOKEN", browser.csrf), 204);
        browser.send(get("/api/v1/auth/me"), 401);
        browser.csrf();
        browser.send(delete("/api/v1/auth/sessions/current").header("X-CSRF-TOKEN", browser.csrf), 204);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean FixtureService fixtureService() { return new FixtureService(); }
        @Bean FixtureController fixtureController(FixtureService service) { return new FixtureController(service); }
    }

    public static class FixtureService {
        @PreAuthorize(RoleAuthorities.REQUIRES_CUSTOMER)
        public String customer() { return "customer"; }
        @PreAuthorize(RoleAuthorities.REQUIRES_STAFF)
        public String staff() { return "staff"; }
        @PreAuthorize(RoleAuthorities.REQUIRES_ADMIN)
        public String admin() { return "admin"; }
    }

    @TestComponent // Excluded from normal component scanning; imported only by this test.
    @RestController
    public static class FixtureController {
        private final FixtureService service;
        public FixtureController(FixtureService service) { this.service = service; }
        @GetMapping(CUSTOMER_PATH)
        public Map<String, String> customer() { return Map.of("permission", service.customer()); }
        @GetMapping(STAFF_PATH)
        public Map<String, String> staff() { return Map.of("permission", service.staff()); }
        @GetMapping(ADMIN_PATH)
        public Map<String, String> admin() { return Map.of("permission", service.admin()); }
        @GetMapping(CUSTOMER_PATH + "/staff-method")
        public Map<String, String> staffMethod() { return Map.of("permission", service.staff()); }
        @GetMapping(CUSTOMER_PATH + "/admin-method")
        @PreAuthorize(RoleAuthorities.REQUIRES_ADMIN)
        public Map<String, String> adminMethod() { return Map.of("permission", "admin-controller"); }
        @PostMapping(STAFF_PATH)
        public Map<String, String> staffMutation() { return Map.of("permission", service.staff()); }
    }
}
