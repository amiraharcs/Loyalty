package com.amiraharcs.loyalty.common.config;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** Explicit browser origins for API requests; CORS does not grant API permissions. */
@Configuration(proxyBeanMethods = false)
public class CorsConfiguration {
    @Bean
    UrlBasedCorsConfigurationSource corsConfigurationSource(
            @Value("${loyalty.cors.allowed-origins:}") String configuredOrigins) {
        List<String> origins = Arrays.stream(configuredOrigins.split(","))
                .map(String::trim).filter(origin -> !origin.isEmpty()).distinct().toList();
        origins.forEach(CorsConfiguration::validateOrigin);
        var cors = new org.springframework.web.cors.CorsConfiguration();
        cors.setAllowedOrigins(origins);
        cors.setAllowCredentials(true);
        cors.setAllowedMethods(List.of("GET", "HEAD", "POST", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Accept", "Content-Type", "X-CSRF-TOKEN", "Idempotency-Key"));
        cors.setExposedHeaders(List.of("X-Request-ID", "Location", "Retry-After"));
        cors.setMaxAge(600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/**", cors);
        // Explicit denial elsewhere also prevents MVC/Actuator preflight fallbacks.
        source.registerCorsConfiguration("/**", new org.springframework.web.cors.CorsConfiguration());
        return source;
    }

    private static void validateOrigin(String value) {
        try {
            URI origin = URI.create(value);
            if ((!"http".equals(origin.getScheme()) && !"https".equals(origin.getScheme()))
                    || origin.getHost() == null || value.contains("*")
                    || origin.getRawUserInfo() != null || origin.getRawQuery() != null
                    || origin.getRawFragment() != null || !origin.getRawPath().isEmpty()
                    || origin.getPort() > 65535) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            // Do not print an invalid setting: it may accidentally contain a secret.
            throw new IllegalArgumentException("CORS_ALLOWED_ORIGINS must contain explicit HTTP(S) origins without paths, wildcards or credentials");
        }
    }
}
