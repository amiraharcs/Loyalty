package com.amiraharcs.loyalty;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
class LoyaltyBackendApplicationTests extends FoundationContextTests {

    @Test
    void defaultsToLocalDevelopment() {
        assertThat(environment.getDefaultProfiles()).containsExactly("dev");
        assertThat(environment.getProperty("server.address")).isEqualTo("127.0.0.1");
    }
}
