package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.api.RegistrationRequest;
import com.amiraharcs.loyalty.auth.service.RegistrationService;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "session.production-cookie-fixture=true")
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class ProductionSessionTests {
    @Autowired MockMvc mvc;
    @Autowired RegistrationService registration;
    @Autowired JsonMapper json;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) { PostgresTestDatabase.register(registry); }

    @Test
    void productionSessionCookieAndItsLogoutDeletionUseTheExactHostOnlyPolicy() throws Exception {
        String email = UUID.randomUUID() + "@example.org";
        String password = "a unique café private phrase 937!";
        registration.register(new RegistrationRequest(email, password, "Haroon"));
        var bootstrap = mvc.perform(get("/api/v1/auth/csrf").secure(true)).andExpect(status().isOk()).andReturn();
        Cookie csrf = bootstrap.getResponse().getCookie("__Host-loyalty-csrf");
        String token = json.readTree(bootstrap.getResponse().getContentAsString()).get("token").asText();
        var login = mvc.perform(post("/api/v1/auth/sessions").secure(true).cookie(csrf)
                        .header("X-CSRF-TOKEN", token).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isCreated()).andReturn();
        Cookie session = login.getResponse().getCookie("__Host-loyalty-session");
        assertHostCookie(session);
        assertThat(login.getResponse().getCookie("loyalty-session")).isNull();
        mvc.perform(get("/api/v1/auth/me").secure(true).cookie(session)).andExpect(status().isOk());
        var refreshed = mvc.perform(get("/api/v1/auth/csrf").secure(true).cookie(session))
                .andExpect(status().isOk()).andReturn();
        Cookie freshCsrf = refreshed.getResponse().getCookie("__Host-loyalty-csrf");
        String freshToken = json.readTree(refreshed.getResponse().getContentAsString()).get("token").asText();
        var logout = mvc.perform(delete("/api/v1/auth/sessions/current").secure(true).cookie(session, freshCsrf)
                        .header("X-CSRF-TOKEN", freshToken))
                .andExpect(status().isNoContent()).andReturn();
        Cookie expired = logout.getResponse().getCookie("__Host-loyalty-session");
        assertHostCookie(expired);
        assertThat(expired.getMaxAge()).isZero();
        mvc.perform(get("/api/v1/auth/me").secure(true).cookie(session)).andExpect(status().isUnauthorized());
    }

    private static void assertHostCookie(Cookie cookie) {
        assertThat(cookie).isNotNull();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getDomain()).isNull();
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
    }
}
