package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.api.RegistrationRequest;
import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.auth.service.RegistrationService;
import com.amiraharcs.loyalty.customer.model.CustomerProfile;
import com.amiraharcs.loyalty.customer.repository.CustomerProfileRepository;
import com.amiraharcs.loyalty.common.api.ConflictException;
import jakarta.persistence.EntityManager;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class RegistrationTests {
    private static final String PASSWORD = "  栗色 coffee constellation 73!  ";
    private static final AtomicInteger ADDRESS = new AtomicInteger();
    @Autowired MockMvc mvc;
    @Autowired RegistrationService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired JsonMapper json;
    @Autowired EntityManager entities;
    @MockitoSpyBean CustomerProfileRepository profiles;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) { PostgresTestDatabase.register(registry); }

    private String email() { return "member-" + UUID.randomUUID() + "@example.org"; }
    private RegistrationRequest request(String email) { return new RegistrationRequest(email, PASSWORD, "Member"); }
    private MockHttpServletRequestBuilder postRegistration(String body) {
        return post("/api/v1/auth/registrations").with(csrf()).contentType("application/json").content(body)
                .with(request -> { request.setRemoteAddr("192.0.2." + ADDRESS.incrementAndGet()); return request; });
    }
    private String body(String email, String password, String name) throws Exception {
        return json.writeValueAsString(Map.of("email", email, "password", password, "displayName", name));
    }
    private int count(String table, UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM loyalty." + table + " WHERE user_id = ?", Integer.class, userId);
    }

    @Test
    void registersPublicCustomerWithNormalizedEmailPrivateProfileAndNoAuthSession(CapturedOutput logs) throws Exception {
        String email = email();
        var result = mvc.perform(postRegistration(body("  " + email.toUpperCase(java.util.Locale.ROOT) + "  ", PASSWORD, "Haroon")))
                .andExpect(status().isCreated()).andExpect(jsonPath("message").value("Registration completed."))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("id").doesNotExist()).andExpect(jsonPath("email").doesNotExist())
                .andExpect(jsonPath("roles").doesNotExist()).andExpect(jsonPath("passwordHash").doesNotExist())
                .andReturn();
        var row = jdbc.queryForMap("SELECT id, password_hash, status FROM loyalty.users WHERE canonical_email = ?", email);
        UUID id = (UUID) row.get("id");
        String hash = (String) row.get("password_hash");
        assertThat(hash).startsWith("{argon2id-v1}$argon2id$v=19$m=19456,t=2,p=1$");
        assertThat(encoder.matches(PASSWORD, hash)).isTrue();
        assertThat(encoder.matches(PASSWORD.strip(), hash)).isFalse();
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForList("SELECT role_code FROM loyalty.user_roles WHERE user_id = ?", String.class, id))
                .containsExactly(RoleCode.CUSTOMER.name());
        var profile = jdbc.queryForMap("SELECT display_name, leaderboard_alias, leaderboard_private FROM loyalty.customer_profiles WHERE user_id = ?", id);
        assertThat(profile.get("display_name")).isEqualTo("Haroon");
        assertThat(profile.get("leaderboard_alias")).isEqualTo("Member");
        assertThat(profile.get("leaderboard_private")).isEqualTo(true);
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(PASSWORD, hash);
        assertThat(logs.getAll()).doesNotContain(PASSWORD, hash);
    }

    @Test
    void canonicalDuplicateEmailReturnsGenericConflictWithoutDatabaseOrPasswordDetails(CapturedOutput logs) throws Exception {
        String email = email();
        service.register(request(email));
        var result = mvc.perform(postRegistration(body(email.toUpperCase(java.util.Locale.ROOT), PASSWORD, "Member")))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("CONFLICT"))
                .andExpect(content().contentType("application/problem+json")).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(PASSWORD, email, "uq_users", "SQL", "password_hash", "trace");
        assertThat(logs.getAll()).doesNotContain(PASSWORD);
    }

    @Test
    void concurrentDuplicateRegistrationCreatesExactlyOneCompleteIdentity() throws Exception {
        String email = email();
        var gate = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> attempt = () -> {
                gate.await();
                try { service.register(request(email)); return true; }
                catch (ConflictException expected) { return false; }
            };
            var first = workers.submit(attempt);
            var second = workers.submit(attempt);
            gate.countDown();
            assertThat(java.util.List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        UUID id = jdbc.queryForObject("SELECT id FROM loyalty.users WHERE canonical_email = ?", UUID.class, email);
        assertThat(count("customer_profiles", id)).isEqualTo(1);
        assertThat(count("user_roles", id)).isEqualTo(1);
    }

    @Test
    void failureAfterUserAndGrantFlushRollsBackAllIdentityRecords() {
        String email = email();
        var attemptedId = new AtomicReference<UUID>();
        doAnswer(invocation -> {
            CustomerProfile profile = invocation.getArgument(0);
            attemptedId.set(profile.getUser().getId());
            entities.flush(); // Ensure user and role INSERTs happened before the profile failure.
            throw new IllegalStateException("Synthetic profile persistence failure");
        }).when(profiles).save(any(CustomerProfile.class));
        assertThatThrownBy(() -> service.register(request(email))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.users WHERE canonical_email = ?", Integer.class, email)).isZero();
        assertThat(count("user_roles", attemptedId.get())).isZero();
        assertThat(count("customer_profiles", attemptedId.get())).isZero();
    }

    @Test
    void invalidServiceRequestIsRejectedBeforePersistence() {
        assertThatThrownBy(() -> service.register(new RegistrationRequest("not-an-email", "short", "")))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "passwordpassword", "123456789012345", ""})
    void invalidPasswordReturnsSafeValidationError(String password) throws Exception {
        var response = mvc.perform(postRegistration(body(email(), password, "Member")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("fieldErrors[0].field").value("password"))
                .andExpect(jsonPath("fieldErrors[0].code").value("INVALID_PASSWORD"))
                .andReturn().getResponse().getContentAsString();
        if (!password.isEmpty()) { assertThat(response).doesNotContain(password); }
    }

    @Test
    void missingFieldsAreRequiredAndInvalidEmailOrNameIsRejected() throws Exception {
        mvc.perform(postRegistration("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("fieldErrors.length()").value(3));
        mvc.perform(postRegistration(body("not-an-email", PASSWORD, "Member")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("fieldErrors[0].code").value("INVALID_FORMAT"));
        mvc.perform(postRegistration(body(email(), PASSWORD, "x".repeat(101))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("fieldErrors[0].code").value("INVALID_SIZE"));
    }

    @Test
    void unknownPrivilegeFieldsAreRejectedWithoutCreatingAUser() throws Exception {
        String email = email();
        mvc.perform(postRegistration(body(email, PASSWORD, "Member").replace("}", ",\"roles\":[\"ADMIN\"],\"status\":\"DISABLED\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("MALFORMED_REQUEST"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.users WHERE canonical_email = ?", Integer.class, email)).isZero();
    }

    @Test
    void missingOrInvalidCsrfFailsAndUnrelatedAuthEndpointsStayProtected() throws Exception {
        mvc.perform(post("/api/v1/auth/registrations").contentType("application/json").content(body(email(), PASSWORD, "Member")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("code").value("FORBIDDEN"));
        mvc.perform(post("/api/v1/auth/registrations").header("X-CSRF-TOKEN", "invalid").contentType("application/json")
                        .content(body(email(), PASSWORD, "Member")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/customers/me/profile")).andExpect(status().isUnauthorized());
    }

    @Test
    void registrationRateLimitDoesNotTrustForwardedIpHeaders() throws Exception {
        String address = "198.51.100.42";
        for (int attempt = 0; attempt < 10; attempt++) {
            mvc.perform(post("/api/v1/auth/registrations").with(csrf()).contentType("application/json").content("{}")
                            .with(request -> { request.setRemoteAddr(address); return request; }))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/v1/auth/registrations").with(csrf()).contentType("application/json").content("{}")
                        .header("X-Forwarded-For", "203.0.113.77")
                        .with(request -> { request.setRemoteAddr(address); return request; }))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("code").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
    }
}
