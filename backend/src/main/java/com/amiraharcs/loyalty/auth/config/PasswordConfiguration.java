package com.amiraharcs.loyalty.auth.config;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
public class PasswordConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() {
        // Architecture: Argon2id, 19 MiB, two iterations, one lane; random 16-byte salt.
        return new DelegatingPasswordEncoder("argon2id-v1", Map.of("argon2id-v1",
                new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2)));
    }
}
