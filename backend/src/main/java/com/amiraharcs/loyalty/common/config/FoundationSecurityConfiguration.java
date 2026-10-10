package com.amiraharcs.loyalty.common.config;

import com.amiraharcs.loyalty.common.api.ApiErrorCode;
import com.amiraharcs.loyalty.common.api.ApiErrorWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/** Closed foundation policy until authentication and endpoint permissions are implemented. */
@Configuration(proxyBeanMethods = false)
class FoundationSecurityConfiguration {

    @Bean
    SecurityFilterChain foundationSecurityFilterChain(HttpSecurity http, ApiErrorWriter errors) throws Exception {
        return http
                .authorizeHttpRequests(authorize -> authorize.anyRequest().denyAll())
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
