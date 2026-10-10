package com.amiraharcs.loyalty;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

@SpringBootTest(properties = "CORS_ALLOWED_ORIGINS=")
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class ProductionConfigurationTests extends FoundationContextTests {

    @Test
    void missingProductionOriginsRejectCrossOriginRequests() throws Exception {
        mvc.perform(options("/api/v1/not-implemented")
                        .header("Origin", "http://localhost:5173").header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header()
                        .doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void missingCorsOriginsDoNotBlockSameOriginRequestsOrGrantApiPermissions() throws Exception {
        mvc.perform(get("/api/v1/not-implemented").header("Origin", "http://localhost"))
                .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void loadsProductionSettingsWithoutTheDevelopmentBindAddress() {
        assertThat(environment.getActiveProfiles()).containsExactly("prod");
        assertThat(environment.getProperty("server.address")).isNull();
        assertThat(environment.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase")).isEqualTo("20s");
    }
}
