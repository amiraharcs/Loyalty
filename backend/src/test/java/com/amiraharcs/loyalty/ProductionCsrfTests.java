package com.amiraharcs.loyalty;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "registration.csrf.production-fixture=true")
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class ProductionCsrfTests {
    @Autowired MockMvc mvc;
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) { PostgresTestDatabase.register(registry); }

    @Test
    void productionCsrfCookieIsHostOnlySecureHttpOnlyAndDoesNotAuthenticate() throws Exception {
        var result = mvc.perform(get("/api/v1/auth/csrf").secure(true)).andExpect(status().isOk()).andReturn();
        var cookie = result.getResponse().getCookie("__Host-loyalty-csrf");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getDomain()).isNull();
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
        assertThat(result.getRequest().getSession(false)).isNull();
        mvc.perform(get("/api/v1/auth/me").secure(true).cookie(cookie)).andExpect(status().isUnauthorized());
    }
}
