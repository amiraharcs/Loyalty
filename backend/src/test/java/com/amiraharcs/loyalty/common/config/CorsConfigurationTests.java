package com.amiraharcs.loyalty.common.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigurationTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CorsConfiguration.class);

    @ParameterizedTest
    @ValueSource(strings = {"*", "https://*.example", "https://example/path", "https://user:secret@example",
            "https://example?secret=value", "https://example#fragment", "null", "file:///tmp", "https://example:99999"})
    void refusesInvalidOrWildcardOriginsWithoutEchoingConfiguration(String origin) {
        runner.withPropertyValues("loyalty.cors.allowed-origins=" + origin).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "CORS_ALLOWED_ORIGINS must contain explicit HTTP(S) origins without paths, wildcards or credentials");
        });
    }

    @Test
    void supportsConfiguredDevelopmentOriginWithoutWildcardPermissions() {
        runner.withPropertyValues("loyalty.cors.allowed-origins=http://localhost:5174").run(context -> {
            assertThat(context).hasNotFailed();
            var request = new MockHttpServletRequest("GET", "/api/v1/example");
            var cors = context.getBean(UrlBasedCorsConfigurationSource.class).getCorsConfiguration(request);
            assertThat(cors.getAllowedOrigins()).containsExactly("http://localhost:5174");
            assertThat(cors.getAllowedOriginPatterns()).isNull();
            assertThat(cors.getAllowedMethods()).doesNotContain("*", "PUT");
            assertThat(cors.getAllowedHeaders()).doesNotContain("*", "Authorization");
        });
    }
}
