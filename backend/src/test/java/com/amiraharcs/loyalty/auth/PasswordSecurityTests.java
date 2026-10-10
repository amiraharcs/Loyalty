package com.amiraharcs.loyalty.auth;

import com.amiraharcs.loyalty.auth.api.RegistrationRequest;
import com.amiraharcs.loyalty.auth.config.PasswordConfiguration;
import com.amiraharcs.loyalty.auth.validation.SecurePasswordValidator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.*;

class PasswordSecurityTests {
    private final SecurePasswordValidator policy = new SecurePasswordValidator();

    @Test
    void passwordPolicyCountsUnicodeCodePointsAndEnforcesBoundariesWithoutTrimming() {
        assertThat(policy.isValid("abcdefghijklmno", null)).isTrue();
        assertThat(policy.isValid("abcdefghijklmno".substring(1), null)).isFalse();
        assertThat(policy.isValid("𐐀".repeat(15), null)).isTrue();
        assertThat(policy.isValid("𐐀".repeat(14), null)).isFalse();
        assertThat(policy.isValid("界".repeat(128), null)).isTrue();
        assertThat(policy.isValid("界".repeat(129), null)).isFalse();
        assertThat(policy.isValid("  space preserving phrase  ", null)).isTrue();
        assertThat(policy.isValid(" ".repeat(15), null)).isFalse();
        assertThat(policy.isValid("PASSWORDPASSWORD", null)).isFalse();
        assertThat(policy.isValid("abcdefghijklmno\uD800", null)).isFalse();
        assertThat(new RegistrationRequest("user@example.org", "private phrase 12345", "Member").toString())
                .doesNotContain("private phrase", "user@example.org");
    }

    @Test
    void encoderUsesVersionedArgon2idRandomSaltsAndPreservesLongUnicodePasswords() {
        new ApplicationContextRunner().withUserConfiguration(PasswordConfiguration.class).run(context -> {
            var encoder = context.getBean(PasswordEncoder.class);
            String password = " 🌠 long Unicode password phrase " + "界".repeat(70);
            long start = System.nanoTime();
            String first = encoder.encode(password);
            String second = encoder.encode(password);
            assertThat(first).startsWith("{argon2id-v1}$argon2id$v=19$m=19456,t=2,p=1$");
            assertThat(second).isNotEqualTo(first);
            assertThat(encoder.matches(password, first)).isTrue();
            assertThat(encoder.matches(password + "!", first)).isFalse();
            assertThat(encoder.matches(password.strip(), first)).isFalse();
            System.out.println("Argon2 local encode/verify benchmark (5 operations): "
                    + (System.nanoTime() - start) / 1_000_000 + " ms; production hardware must be benchmarked separately.");
        });
    }
}
