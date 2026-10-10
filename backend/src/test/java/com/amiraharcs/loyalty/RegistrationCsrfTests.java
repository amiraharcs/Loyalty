package com.amiraharcs.loyalty;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Separate context: Spring Security's csrf() test helper replaces the real repository. */
@SpringBootTest(properties = "registration.csrf.real-repository-fixture=true")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class RegistrationCsrfTests {
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) { PostgresTestDatabase.register(registry); }

    @Test
    void realCookieAndMaskedTokenRoundTripCreatesNoAuthSession(CapturedOutput logs) throws Exception {
        var bootstrap = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("headerName").value("X-CSRF-TOKEN")).andReturn();
        String token = json.readTree(bootstrap.getResponse().getContentAsString()).get("token").asText();
        var cookie = bootstrap.getResponse().getCookie("loyalty-csrf");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
        assertThat(token).isNotEqualTo(cookie.getValue());
        assertThat(bootstrap.getRequest().getSession(false)).isNull();
        String password = "  starry café voyage 937!  ";
        String body = json.writeValueAsString(Map.of("email", UUID.randomUUID() + "@example.org",
                "password", password, "displayName", "Member"));
        var result = mvc.perform(post("/api/v1/auth/registrations").cookie(cookie).header("X-CSRF-TOKEN", token)
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();
        assertThat(logs.getAll()).doesNotContain(token, cookie.getValue(), password);
    }
}
