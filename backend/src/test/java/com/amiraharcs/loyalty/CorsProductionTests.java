package com.amiraharcs.loyalty;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "CORS_ALLOWED_ORIGINS=https://frontend.example,https://second.example")
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class CorsProductionTests {
    @Autowired MockMvc mvc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) { PostgresTestDatabase.register(registry); }

    @Test
    void onlyExplicitProductionOriginsAreAllowed() throws Exception {
        for (String origin : new String[]{"https://frontend.example", "https://second.example"}) {
            mvc.perform(options("/api/v1/not-implemented").header("Origin", origin)
                            .header("Access-Control-Request-Method", "PATCH"))
                    .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", origin))
                    .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
            mvc.perform(get("/api/v1/not-implemented").header("Origin", origin))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("UNAUTHENTICATED"));
        }
        mvc.perform(options("/api/v1/not-implemented").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
