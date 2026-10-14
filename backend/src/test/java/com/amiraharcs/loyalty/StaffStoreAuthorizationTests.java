package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.api.RegistrationRequest;
import com.amiraharcs.loyalty.auth.model.AccountStatus;
import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.auth.model.User;
import com.amiraharcs.loyalty.auth.repository.RoleRepository;
import com.amiraharcs.loyalty.auth.repository.UserRepository;
import com.amiraharcs.loyalty.auth.service.RegistrationService;
import com.amiraharcs.loyalty.store.model.StaffStore;
import com.amiraharcs.loyalty.store.model.Store;
import com.amiraharcs.loyalty.store.model.StoreStatus;
import com.amiraharcs.loyalty.store.repository.StaffStoreRepository;
import com.amiraharcs.loyalty.store.repository.StoreRepository;
import com.amiraharcs.loyalty.store.service.StaffStoreScopeService;
import com.amiraharcs.loyalty.store.service.StoreService;
import com.amiraharcs.loyalty.store.service.StoreSummary;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.BadCredentialsException;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Tests store scope through actual PostgreSQL sessions and a controller available only in tests. */
@SpringBootTest(properties = "store.real-session-fixture=true")
@AutoConfigureMockMvc
@Import(StaffStoreAuthorizationTests.Fixtures.class)
class StaffStoreAuthorizationTests {
    private static final String PATH = "/api/v1/staff/_store-scope-fixture";
    private static final String PASSWORD = "a unique café store scope phrase 937!";
    private static final AtomicInteger IPS = new AtomicInteger();
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired RegistrationService registration;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired StoreRepository stores;
    @Autowired StaffStoreRepository assignments;
    @Autowired StaffStoreScopeService scope;
    @Autowired StoreService catalogue;
    @Autowired JdbcIndexedSessionRepository sessions;
    @Autowired Clock clock;
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) { PostgresTestDatabase.register(registry); }

    private class Browser {
        final Map<String, Cookie> cookies = new HashMap<>();
        final String ip = "203.0.113." + IPS.incrementAndGet();
        UUID userId;
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
        String id() { return cookies.get("loyalty-session").getValue(); }
    }
    private Browser login(RoleCode... grants) throws Exception {
        String email = UUID.randomUUID() + "@example.org";
        registration.register(new RegistrationRequest(email, PASSWORD, "Haroon"));
        UUID userId = jdbc.queryForObject("SELECT id FROM loyalty.users WHERE canonical_email = ?", UUID.class, email);
        jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id = ?", userId);
        for (var role : grants) { jdbc.update("INSERT INTO loyalty.user_roles (user_id, role_code) VALUES (?, ?)", userId, role.name()); }
        var browser = new Browser();
        var csrf = browser.send(get("/api/v1/auth/csrf"), 200);
        String token = json.readTree(csrf.getResponse().getContentAsString()).get("token").asText();
        browser.send(post("/api/v1/auth/sessions").header("X-CSRF-TOKEN", token).contentType("application/json")
                .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))), 201);
        browser.userId = userId;
        return browser;
    }
    private Store store(StoreStatus status) {
        return stores.save(new Store("Synthetic shop " + UUID.randomUUID(), "Synthetic fixture address", status, clock.instant()));
    }
    private StaffStore assign(Browser browser, Store store) {
        var assigner = users.save(new User(UUID.randomUUID() + "@example.org", "synthetic-not-login-hash", AccountStatus.ACTIVE,
                clock.instant(), Set.of(roles.findById(RoleCode.ADMIN).orElseThrow())));
        return assignments.save(new StaffStore(users.findById(browser.userId).orElseThrow(), store, assigner, clock.instant()));
    }
    private void assume(Browser browser) {
        Session session = sessions.findById(browser.id());
        SecurityContext context = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        SecurityContextHolder.setContext(context);
    }
    private MvcResult request(Browser browser, UUID storeId, int expected) throws Exception {
        var result = browser.send(get(PATH + "/" + storeId), expected);
        if (expected != 200) {
            String code = switch (expected) { case 401 -> "UNAUTHENTICATED"; case 403 -> "FORBIDDEN"; default -> "RESOURCE_NOT_FOUND"; };
            var error = json.readTree(result.getResponse().getContentAsString());
            assertThat(error.get("code").asText()).isEqualTo(code);
            assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
            assertThat(result.getResponse().getContentAsString()).doesNotContain("assignedBy", "staffUserId", "password", "trace", "Synthetic");
        }
        return result;
    }

    @ParameterizedTest
    @CsvSource({"CUSTOMER,403", "ADMIN,403", "CUSTOMER|ADMIN,403", "EMPTY,403", "STAFF,200", "STAFF|ADMIN,200", "CUSTOMER|STAFF,200", "CUSTOMER|STAFF|ADMIN,200"})
    void assignmentRequiresExplicitStaffRoleAtRouteAndDirectServiceLevels(String grants, int expected) throws Exception {
        var browser = login(grants.equals("EMPTY") ? new RoleCode[0]
                : Arrays.stream(grants.split("\\|")).map(RoleCode::valueOf).toArray(RoleCode[]::new));
        var store = store(StoreStatus.ACTIVE); assign(browser, store);
        var result = request(browser, store.getId(), expected);
        assertThat(AopUtils.isAopProxy(scope)).isTrue();
        try {
            assume(browser);
            if (expected == 200) {
                assertThat(scope.requireAssignedStore(store.getId()).id()).isEqualTo(store.getId());
                assertThat(json.readTree(result.getResponse().getContentAsString()).get("id").asText()).isEqualTo(store.getId().toString());
                assertThat(result.getResponse().getContentAsString()).doesNotContain("staffUser", "assignedBy", "password", "active", "status");
            } else { assertThatThrownBy(() -> scope.requireAssignedStore(store.getId())).isInstanceOf(AccessDeniedException.class); }
        } finally { SecurityContextHolder.clearContext(); }
    }

    @Test
    void realStaffSessionCannotCreateVisitThroughArbitraryCustomerOrClientTimePayload() throws Exception {
        var browser = login(RoleCode.STAFF); var store = store(StoreStatus.ACTIVE); assign(browser, store);
        long before = jdbc.queryForObject("SELECT count(*) FROM loyalty.visits", Long.class);
        var csrf = browser.send(get("/api/v1/auth/csrf"), 200);
        String token = json.readTree(csrf.getResponse().getContentAsString()).get("token").asText();
        browser.send(post("/api/v1/staff/visits").header("X-CSRF-TOKEN", token).contentType("application/json")
                .content(json.writeValueAsString(Map.of("customerId", UUID.randomUUID(), "storeId", store.getId(),
                        "businessDate", "2050-01-01", "confirmedAt", "2050-01-01T00:00:00Z", "timezone", "Pacific/Auckland", "earnedPoints", 999999, "points", 999999))), 400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.visits", Long.class)).isEqualTo(before);
    }

    @Test
    void anonymousCannotReachScopeOrDirectlyInvokeTheService() throws Exception {
        request(new Browser(), store(StoreStatus.ACTIVE).getId(), 401);
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> scope.requireAssignedStore(UUID.randomUUID())).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void staffWithoutAssignmentCannotBorrowAnotherStaffIdentityThroughRequestParameters() throws Exception {
        var assigned = login(RoleCode.STAFF); var other = login(RoleCode.STAFF);
        var store = store(StoreStatus.ACTIVE); assign(assigned, store);
        request(assigned, store.getId(), 200);
        request(other, store.getId(), 404);
        other.send(get(PATH + "/" + store.getId()).param("staffUserId", assigned.userId.toString()), 404);
        try {
            assume(other);
            assertThatThrownBy(() -> scope.requireAssignedStore(store.getId())).isInstanceOf(com.amiraharcs.loyalty.common.api.ResourceNotFoundException.class);
        } finally { SecurityContextHolder.clearContext(); }
    }

    @Test
    void inactiveAssignmentInactiveStoreAndAbsentStoreReturnTheSameSafeNotFoundDetail() throws Exception {
        var browser = login(RoleCode.STAFF);
        var unassigned = store(StoreStatus.ACTIVE);
        var inactive = store(StoreStatus.INACTIVE); assign(browser, inactive);
        var deassigned = store(StoreStatus.ACTIVE); var link = assign(browser, deassigned);
        link.setActive(false, clock.instant()); assignments.save(link);
        String detail = null;
        for (var id : List.of(unassigned.getId(), inactive.getId(), deassigned.getId(), UUID.randomUUID())) {
            var error = json.readTree(request(browser, id, 404).getResponse().getContentAsString());
            if (detail == null) { detail = error.get("detail").asText(); }
            else { assertThat(error.get("detail").asText()).isEqualTo(detail); }
        }
    }

    @Test
    void assignmentDeactivationTakesEffectOnTheNextRequestWithoutEndingOrCachingTheSession() throws Exception {
        var browser = login(RoleCode.STAFF); var store = store(StoreStatus.ACTIVE); var link = assign(browser, store);
        request(browser, store.getId(), 200); String id = browser.id();
        link.setActive(false, clock.instant()); assignments.save(link);
        request(browser, store.getId(), 404);
        assertThat(browser.id()).isEqualTo(id);
        browser.send(get("/api/v1/auth/me"), 200);
        assertThat(assignments.findById(link.getId())).isPresent();
        link.setActive(true, clock.instant()); assignments.save(link);
        request(browser, store.getId(), 200);
    }

    @Test
    void storeDeactivationTakesEffectOnTheNextRequestAndKeepsAssignmentHistory() throws Exception {
        var browser = login(RoleCode.STAFF); var store = store(StoreStatus.ACTIVE); var link = assign(browser, store);
        request(browser, store.getId(), 200);
        store.setStatus(StoreStatus.INACTIVE, clock.instant()); stores.save(store);
        request(browser, store.getId(), 404);
        assertThat(assignments.findById(link.getId()).orElseThrow().isActive()).isTrue();
        assertThat(stores.findById(store.getId())).isPresent();
        store.setStatus(StoreStatus.ACTIVE, clock.instant()); stores.save(store);
        request(browser, store.getId(), 200);
    }

    @Test
    void assignedStoreListContainsOnlyCurrentActiveScopeAndSafeProjectionFields() throws Exception {
        var browser = login(RoleCode.STAFF); var other = login(RoleCode.STAFF);
        var active = store(StoreStatus.ACTIVE); assign(browser, active);
        var inactive = store(StoreStatus.INACTIVE); assign(browser, inactive);
        var deassigned = store(StoreStatus.ACTIVE); var link = assign(browser, deassigned);
        link.setActive(false, clock.instant()); assignments.save(link);
        assign(other, store(StoreStatus.ACTIVE));
        var result = browser.send(get(PATH), 200);
        var list = json.readTree(result.getResponse().getContentAsString());
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).get("id").asText()).isEqualTo(active.getId().toString());
        assertThat(result.getResponse().getContentAsString()).doesNotContain(browser.userId.toString(), "assignedBy", "staffUserId", "password");
        try {
            assume(browser);
            assertThat(scope.assignedActiveStores()).extracting(StoreSummary::id).containsExactly(active.getId());
        } finally { SecurityContextHolder.clearContext(); }
    }

    @Test
    void directServiceRechecksDatabaseRolesInsteadOfTrustingTheSavedAuthoritySnapshot() throws Exception {
        var browser = login(RoleCode.STAFF); var store = store(StoreStatus.ACTIVE); assign(browser, store);
        try {
            assume(browser);
            assertThat(scope.requireAssignedStore(store.getId()).id()).isEqualTo(store.getId());
            jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id = ? AND role_code = 'STAFF'", browser.userId);
            assertThatThrownBy(() -> scope.requireAssignedStore(store.getId())).isInstanceOf(AccessDeniedException.class);
        } finally { SecurityContextHolder.clearContext(); }
        request(browser, store.getId(), 403);
    }

    @Test
    void directServiceAndHttpBothBlockDisabledAccountsEvenWithAnOldSessionAndAssignment() throws Exception {
        var browser = login(RoleCode.STAFF); var store = store(StoreStatus.ACTIVE); var link = assign(browser, store);
        String id = browser.id();
        try {
            assume(browser);
            jdbc.update("UPDATE loyalty.users SET status = 'DISABLED' WHERE id = ?", browser.userId);
            assertThatThrownBy(() -> scope.requireAssignedStore(store.getId())).isInstanceOf(BadCredentialsException.class);
        } finally { SecurityContextHolder.clearContext(); }
        request(browser, store.getId(), 401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.spring_session WHERE session_id = ?", Integer.class, id)).isZero();
        assertThat(assignments.findById(link.getId())).isPresent();
    }

    @Test
    void activeStoreServiceReturnsSafeDataAndRejectsInactiveOrMissingStores() throws Exception {
        var browser = login(RoleCode.CUSTOMER);
        var active = store(StoreStatus.ACTIVE); var inactive = store(StoreStatus.INACTIVE);
        try {
            assume(browser);
            var summary = catalogue.requireActive(active.getId());
            assertThat(summary).isEqualTo(new StoreSummary(active.getId(), active.getName(), active.getAddress()));
            assertThat(catalogue.activeStores()).extracting(StoreSummary::id).contains(active.getId()).doesNotContain(inactive.getId());
            assertThatThrownBy(() -> catalogue.requireActive(inactive.getId())).isInstanceOf(com.amiraharcs.loyalty.common.api.ResourceNotFoundException.class);
            assertThatThrownBy(() -> catalogue.requireActive(UUID.randomUUID())).isInstanceOf(com.amiraharcs.loyalty.common.api.ResourceNotFoundException.class);
        } finally { SecurityContextHolder.clearContext(); }
        assertThatThrownBy(() -> catalogue.requireActive(active.getId())).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        browser.send(get("/api/v1/stores"), 403); // No production store API was opened by this issue.
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean ScopeController scopeController(StaffStoreScopeService scope) { return new ScopeController(scope); }
    }
    @TestComponent
    @RestController
    public static class ScopeController {
        private final StaffStoreScopeService scope;
        public ScopeController(StaffStoreScopeService scope) { this.scope = scope; }
        @GetMapping(PATH + "/{storeId}")
        public StoreSummary store(@PathVariable UUID storeId) { return scope.requireAssignedStore(storeId); }
        @GetMapping(PATH)
        public List<StoreSummary> stores() { return scope.assignedActiveStores(); }
    }
}
