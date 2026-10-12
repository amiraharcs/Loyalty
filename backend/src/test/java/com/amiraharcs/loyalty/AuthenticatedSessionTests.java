package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.api.RegistrationRequest;
import com.amiraharcs.loyalty.auth.service.RegistrationService;
import com.amiraharcs.loyalty.auth.security.SessionLifecycle;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Uses real CSRF/session cookies and PostgreSQL; no mocked security context or csrf() helper. */
@SpringBootTest(properties = "session.real-cookie-fixture=true")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class AuthenticatedSessionTests {
    private static final String PASSWORD = "  café wandering stars 937!  ";
    @Autowired MockMvc mvc;
    @Autowired RegistrationService registration;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired JdbcIndexedSessionRepository jdbcSessions;

    // JdbcSession is package-private; use the public Session API for test fixtures.
    @SuppressWarnings("unchecked")
    private SessionRepository<Session> sessions() {
        return (SessionRepository<Session>) (SessionRepository<?>) jdbcSessions;
    }
    @Autowired PasswordEncoder encoder;
    @Autowired Clock clock;
    private static final java.util.concurrent.atomic.AtomicInteger IPS = new java.util.concurrent.atomic.AtomicInteger();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) { PostgresTestDatabase.register(registry); }

    private String registered() {
        String email = UUID.randomUUID() + "@example.org";
        registration.register(new RegistrationRequest(email, PASSWORD, "Haroon"));
        return email;
    }
    private UUID userId(String email) { return jdbc.queryForObject("SELECT id FROM loyalty.users WHERE canonical_email = ?", UUID.class, email); }
    private int sessionRows(String id) { return jdbc.queryForObject("SELECT count(*) FROM loyalty.spring_session WHERE session_id = ?", Integer.class, id); }

    private class Browser {
        final Map<String, Cookie> cookies = new HashMap<>();
        final String ip = "192.0.2." + IPS.incrementAndGet();
        String token;
        MvcResult send(MockHttpServletRequestBuilder builder, int status) throws Exception {
            if (!cookies.isEmpty()) { builder.cookie(cookies.values().toArray(Cookie[]::new)); }
            var result = mvc.perform(builder.with(request -> { request.setRemoteAddr(ip); return request; }))
                    .andExpect(status().is(status)).andReturn();
            for (Cookie cookie : result.getResponse().getCookies()) {
                if (cookie.getMaxAge() == 0) { cookies.remove(cookie.getName()); }
                else { cookies.put(cookie.getName(), cookie); }
            }
            return result;
        }
        void csrf() throws Exception {
            var result = send(get("/api/v1/auth/csrf"), 200);
            token = json.readTree(result.getResponse().getContentAsString()).get("token").asText();
        }
        MvcResult login(String email, String password, int status) throws Exception {
            return send(post("/api/v1/auth/sessions").contentType("application/json")
                    .header("X-CSRF-TOKEN", token).content(json.writeValueAsString(Map.of("email", email, "password", password))), status);
        }
        String sessionId() { return cookies.get("loyalty-session").getValue(); }
    }

    @Test
    void loginPersistsSafeIdentityAndCurrentUserWithoutSecrets(CapturedOutput logs) throws Exception {
        String email = registered();
        var browser = new Browser();
        browser.csrf();
        var login = browser.login("  " + email.toUpperCase(Locale.ROOT) + "  ", PASSWORD, 201);
        String sessionId = browser.sessionId();
        Cookie cookie = browser.cookies.get("loyalty-session");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isFalse();
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getDomain()).isNull();
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
        assertThat(sessionRows(sessionId)).isEqualTo(1);
        var dto = json.readTree(login.getResponse().getContentAsString());
        assertThat(dto.get("userId").asText()).isEqualTo(userId(email).toString());
        assertThat(dto.get("displayName").asText()).isEqualTo("Haroon");
        assertThat(dto.get("roles").get(0).asText()).isEqualTo("CUSTOMER");
        assertThat(login.getResponse().getHeader("Location")).isEqualTo("/api/v1/auth/me");
        var me = browser.send(get("/api/v1/auth/me"), 200);
        assertThat(json.readTree(me.getResponse().getContentAsString())).isEqualTo(dto);
        assertThat(me.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        String hash = jdbc.queryForObject("SELECT password_hash FROM loyalty.users WHERE canonical_email = ?", String.class, email);
        assertThat(login.getResponse().getContentAsString()).doesNotContain(email, PASSWORD, hash, sessionId, "password", "credential");
        assertThat(logs.getAll()).doesNotContain(PASSWORD, hash, sessionId);
        var attributes = jdbc.queryForList("SELECT attribute_bytes FROM loyalty.spring_session_attributes "
                + "WHERE session_primary_id = (SELECT primary_id FROM loyalty.spring_session WHERE session_id = ?)", byte[].class, sessionId);
        for (byte[] bytes : attributes) {
            assertThat(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(PASSWORD, hash, email);
        }
    }

    @Test
    void invalidPasswordNonexistentAndDisabledAccountsHaveTheSameGeneric401() throws Exception {
        String email = registered();
        var browser = new Browser(); browser.csrf();
        String mismatch = browser.login(email, "incorrect private phrase", 401).getResponse().getContentAsString();
        String absent = browser.login(UUID.randomUUID() + "@example.org", PASSWORD, 401).getResponse().getContentAsString();
        jdbc.update("UPDATE loyalty.users SET status = 'DISABLED' WHERE canonical_email = ?", email);
        String disabled = browser.login(email, PASSWORD, 401).getResponse().getContentAsString();
        for (String body : List.of(mismatch, absent, disabled)) {
            var error = json.readTree(body);
            assertThat(error.get("code").asText()).isEqualTo("UNAUTHENTICATED");
            assertThat(error.get("detail").asText()).isEqualTo(json.readTree(mismatch).get("detail").asText());
            assertThat(body).doesNotContain(email, PASSWORD, "DISABLED", "password mismatch", "trace");
        }
        assertThat(browser.cookies.get("loyalty-session")).isNull();
    }

    @Test
    void rotatesPreAuthenticationSessionAndRequiresFreshCsrfAfterLogin() throws Exception {
        String email = registered();
        var preauth = sessions().createSession();
        preauth.setAttribute("test", "pre-authentication");
        sessions().save(preauth);
        String oldId = preauth.getId();
        var browser = new Browser(); browser.cookies.put("loyalty-session", new Cookie("loyalty-session", oldId));
        browser.csrf();
        String oldCsrf = browser.token;
        browser.login(email, PASSWORD, 201);
        assertThat(browser.sessionId()).isNotEqualTo(oldId);
        assertThat(sessionRows(oldId)).isZero();
        browser.send(delete("/api/v1/auth/sessions/current").header("X-CSRF-TOKEN", oldCsrf), 403);
        browser.csrf();
        assertThat(browser.token).isNotEqualTo(oldCsrf);
        browser.send(delete("/api/v1/auth/sessions/current").header("X-CSRF-TOKEN", browser.token), 204);
    }

    @Test
    void logoutDeletesSessionAndAttributesExpiresCookieAndIsIdempotentWithFreshCsrf() throws Exception {
        var browser = new Browser(); browser.csrf(); browser.login(registered(), PASSWORD, 201);
        String id = browser.sessionId();
        String primary = jdbc.queryForObject("SELECT primary_id FROM loyalty.spring_session WHERE session_id = ?", String.class, id);
        browser.csrf();
        browser.send(delete("/api/v1/auth/sessions/current").header("X-CSRF-TOKEN", browser.token), 204);
        assertThat(browser.cookies.get("loyalty-session")).isNull();
        assertThat(sessionRows(id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.spring_session_attributes WHERE session_primary_id = ?", Integer.class, primary)).isZero();
        mvc.perform(get("/api/v1/auth/me").cookie(new Cookie("loyalty-session", id))).andExpect(status().isUnauthorized());
        browser.csrf();
        browser.send(delete("/api/v1/auth/sessions/current").header("X-CSRF-TOKEN", browser.token), 204);
        browser.send(get("/api/v1/auth/me"), 401);
    }

    @Test
    void idleTimeoutRejectsSessionAndUsesThirtyMinuteSetting() throws Exception {
        var browser = new Browser(); browser.csrf(); browser.login(registered(), PASSWORD, 201);
        String id = browser.sessionId();
        assertThat(jdbc.queryForObject("SELECT max_inactive_interval FROM loyalty.spring_session WHERE session_id = ?", Integer.class, id)).isEqualTo(1800);
        var session = sessions().findById(id);
        session.setLastAccessedTime(Instant.now().minus(Duration.ofMinutes(31)));
        sessions().save(session);
        browser.send(get("/api/v1/auth/me"), 401);
        assertThat(sessions().findById(id)).isNull();
    }

    @Test
    void absoluteTwelveHourExpiryRejectsOtherwiseActiveSession() throws Exception {
        var browser = new Browser(); browser.csrf(); browser.login(registered(), PASSWORD, 201);
        String id = browser.sessionId();
        var session = sessions().findById(id);
        session.setAttribute(SessionLifecycle.AUTHENTICATED_AT, clock.instant().minus(Duration.ofHours(12)));
        sessions().save(session);
        browser.send(get("/api/v1/auth/me"), 401);
        assertThat(sessionRows(id)).isZero();
    }

    @Test
    void missingAuthenticationTimestampFailsClosed() throws Exception {
        var browser = new Browser(); browser.csrf(); browser.login(registered(), PASSWORD, 201);
        String id = browser.sessionId();
        var session = sessions().findById(id);
        session.removeAttribute(SessionLifecycle.AUTHENTICATED_AT);
        sessions().save(session);
        browser.send(get("/api/v1/auth/me"), 401);
        assertThat(sessionRows(id)).isZero();
    }

    @Test
    void staffIdentityWithoutCustomerProfileDoesNotGainAnImplicitCustomerRole() throws Exception {
        String email = registered();
        UUID id = userId(email);
        // This test identity has no ledger history; production history cannot be cascaded away.
        jdbc.update("DELETE FROM loyalty.loyalty_accounts WHERE customer_id IN "
                + "(SELECT id FROM loyalty.customer_profiles WHERE user_id = ?)", id);
        jdbc.update("DELETE FROM loyalty.customer_profiles WHERE user_id = ?", id);
        jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id = ?", id);
        jdbc.update("INSERT INTO loyalty.user_roles (user_id, role_code) VALUES (?, 'STAFF')", id);
        var browser = new Browser(); browser.csrf();
        var login = browser.login(email, PASSWORD, 201);
        var identity = json.readTree(login.getResponse().getContentAsString());
        assertThat(identity.get("displayName").isNull()).isTrue();
        assertThat(identity.get("roles").toString()).isEqualTo("[\"STAFF\"]");
        browser.send(get("/api/v1/auth/me"), 200);
    }

    @Test
    void disablingAccountInvalidatesExistingAuthenticatedSession() throws Exception {
        String email = registered();
        var browser = new Browser(); browser.csrf(); browser.login(email, PASSWORD, 201);
        String id = browser.sessionId();
        jdbc.update("UPDATE loyalty.users SET status = 'DISABLED' WHERE canonical_email = ?", email);
        browser.send(get("/api/v1/auth/me"), 401);
        assertThat(sessionRows(id)).isZero();
    }

    @Test
    void currentRolesComeFromDatabaseAndRoleChangeRotatesIdWithoutExtendingAbsoluteLife() throws Exception {
        String email = registered();
        var browser = new Browser(); browser.csrf(); browser.login(email, PASSWORD, 201);
        String oldId = browser.sessionId();
        Instant authenticatedAt = sessions().findById(oldId).getAttribute(SessionLifecycle.AUTHENTICATED_AT);
        jdbc.update("INSERT INTO loyalty.user_roles (user_id, role_code) VALUES (?, 'STAFF')", userId(email));
        var result = browser.send(get("/api/v1/auth/me"), 200);
        var roles = json.readTree(result.getResponse().getContentAsString()).get("roles");
        assertThat(roles.toString()).contains("CUSTOMER", "STAFF").doesNotContain("ADMIN");
        assertThat(browser.sessionId()).isNotEqualTo(oldId);
        assertThat(sessionRows(oldId)).isZero();
        assertThat((Instant) sessions().findById(browser.sessionId()).getAttribute(SessionLifecycle.AUTHENTICATED_AT)).isEqualTo(authenticatedAt);
        jdbc.update("DELETE FROM loyalty.user_roles WHERE user_id = ? AND role_code = 'CUSTOMER'", userId(email));
        var removed = browser.send(get("/api/v1/auth/me"), 200);
        assertThat(json.readTree(removed.getResponse().getContentAsString()).get("roles").toString()).isEqualTo("[\"STAFF\"]");
    }

    @Test
    void passwordChangeInvalidatesOldSessionsAndWeakerArgonHashUpgradesAtLogin() throws Exception {
        String email = registered();
        String weaker = "{argon2id-v1}" + new Argon2PasswordEncoder(16, 32, 1, 8192, 1).encode(PASSWORD);
        jdbc.update("UPDATE loyalty.users SET password_hash = ? WHERE canonical_email = ?", weaker, email);
        var browser = new Browser(); browser.csrf(); browser.login(email, PASSWORD, 201);
        String id = browser.sessionId();
        String upgraded = jdbc.queryForObject("SELECT password_hash FROM loyalty.users WHERE canonical_email = ?", String.class, email);
        assertThat(upgraded).contains("m=19456,t=2,p=1");
        assertThat(encoder.matches(PASSWORD, upgraded)).isTrue();
        jdbc.update("UPDATE loyalty.users SET password_hash = ? WHERE canonical_email = ?", encoder.encode("a changed private phrase 24!"), email);
        browser.send(get("/api/v1/auth/me"), 401);
        assertThat(sessionRows(id)).isZero();
    }

    @Test
    void csrfIsRequiredForLoginAndLogoutAndUnrelatedRoutesStayDenied() throws Exception {
        var browser = new Browser(); browser.csrf();
        browser.send(post("/api/v1/auth/sessions").contentType("application/json").content("{}"), 403);
        browser.login(registered(), PASSWORD, 201);
        String id = browser.sessionId();
        browser.send(delete("/api/v1/auth/sessions/current"), 403);
        assertThat(sessionRows(id)).isEqualTo(1);
        browser.send(get("/api/v1/rewards"), 403);
        browser.send(get("/actuator/env"), 403);
    }

    @Test
    void tenFailedAttemptsPerAccountAndIpThrottleEvenCorrectCredentialsGenerically() throws Exception {
        String email = registered();
        var browser = new Browser(); browser.csrf();
        for (int attempt = 0; attempt < 10; attempt++) { browser.login(email, "wrong private phrase", 401); }
        var blocked = browser.login(email, PASSWORD, 401);
        assertThat(json.readTree(blocked.getResponse().getContentAsString()).get("code").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(browser.cookies.get("loyalty-session")).isNull();
        var other = new Browser(); other.csrf(); other.login(email, PASSWORD, 201);
    }

    @Test
    void independentIpBudgetCannotBeBypassedByChangingForwardedHeaders() throws Exception {
        var browser = new Browser(); browser.csrf();
        for (int attempt = 0; attempt < 50; attempt++) {
            browser.send(post("/api/v1/auth/sessions").header("X-CSRF-TOKEN", browser.token)
                    .header("X-Forwarded-For", "203.0.113." + attempt)
                    .contentType("application/json").content("{}"), 400);
        }
        var blocked = browser.login(registered(), PASSWORD, 401);
        assertThat(blocked.getResponse().getHeader("Retry-After")).isNotBlank();
        assertThat(json.readTree(blocked.getResponse().getContentAsString()).get("code").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(browser.cookies.get("loyalty-session")).isNull();
    }

    @Test
    void loggingInAgainRotatesTheAuthenticatedSessionAndRevokesItsOldId() throws Exception {
        String email = registered();
        var browser = new Browser(); browser.csrf(); browser.login(email, PASSWORD, 201);
        String original = browser.sessionId();
        browser.csrf(); browser.login(email, PASSWORD, 201);
        assertThat(browser.sessionId()).isNotEqualTo(original);
        assertThat(sessionRows(original)).isZero();
        mvc.perform(get("/api/v1/auth/me").cookie(new Cookie("loyalty-session", original)))
                .andExpect(status().isUnauthorized());
        browser.send(get("/api/v1/auth/me"), 200);
    }

    @Test
    void validationAndWrongTypedBodiesDoNotExposeCredentials() throws Exception {
        var browser = new Browser(); browser.csrf();
        var result = browser.send(post("/api/v1/auth/sessions").header("X-CSRF-TOKEN", browser.token)
                .contentType("application/json").content("{\"email\":\"invalid\",\"password\":\"private\"}"), 400);
        assertThat(json.readTree(result.getResponse().getContentAsString()).get("code").asText()).isEqualTo("VALIDATION_ERROR");
        browser.send(post("/api/v1/auth/sessions").header("X-CSRF-TOKEN", browser.token)
                .contentType("application/json").content("{}"), 400);
        var malformed = browser.send(post("/api/v1/auth/sessions").header("X-CSRF-TOKEN", browser.token)
                .contentType("application/json").content("{\"email\":{},\"password\":\"private\"}"), 400);
        assertThat(json.readTree(malformed.getResponse().getContentAsString()).get("code").asText()).isEqualTo("MALFORMED_REQUEST");
        assertThat(malformed.getResponse().getContentAsString()).doesNotContain("private", "trace");
    }
}
