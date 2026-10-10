package com.amiraharcs.loyalty.common.config;

import com.amiraharcs.loyalty.common.api.ApiErrorCode;
import com.amiraharcs.loyalty.common.api.ApiErrorWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.http.HttpMethod;
import com.amiraharcs.loyalty.auth.api.RegistrationRateLimitFilter;
import com.amiraharcs.loyalty.auth.service.RegistrationRateLimiter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import com.amiraharcs.loyalty.auth.service.LoginRateLimiter;
import com.amiraharcs.loyalty.auth.service.AuthenticationIdentityService;
import com.amiraharcs.loyalty.auth.security.*;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import java.time.Clock;

/** Explicit public registration/probe routes; all other actions remain denied until implemented. */
@Configuration(proxyBeanMethods = false)
class FoundationSecurityConfiguration {

    @Bean
    SecurityFilterChain foundationSecurityFilterChain(HttpSecurity http, ApiErrorWriter errors,
            UrlBasedCorsConfigurationSource corsConfigurationSource, RegistrationRateLimiter limiter,
            CookieCsrfTokenRepository csrfTokens, SecurityContextRepository contexts, LoginRateLimiter loginLimiter,
            AuthenticationIdentityService identities, Clock clock, SessionLifecycle lifecycle) throws Exception {
        return http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens)
                        .csrfTokenRequestHandler(new XorCsrfTokenRequestAttributeHandler()))
                .securityContext(context -> context.securityContextRepository(contexts).requireExplicitSave(true))
                .requestCache(cache -> cache.disable())
                .addFilterAfter(new SessionStateFilter(identities, clock, contexts, lifecycle, csrfTokens, errors), AnonymousAuthenticationFilter.class)
                .addFilterAfter(new LoginRateLimitFilter(loginLimiter, errors), CsrfFilter.class)
                .addFilterAfter(new RegistrationRateLimitFilter(limiter, errors), CsrfFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/registrations", "/api/v1/auth/sessions").permitAll()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/auth/sessions/current").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/me").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/liveness",
                                "/actuator/health/readiness").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/actuator/health", "/actuator/health/liveness",
                                "/actuator/health/readiness").permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                errors.write(ApiErrorCode.UNAUTHENTICATED, request, response))
                        .accessDeniedHandler((request, response, exception) ->
                                errors.write(ApiErrorCode.FORBIDDEN, request, response)))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .build();
    }
}
