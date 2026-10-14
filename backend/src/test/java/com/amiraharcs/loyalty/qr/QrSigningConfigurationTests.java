package com.amiraharcs.loyalty.qr;

import com.amiraharcs.loyalty.qr.config.*;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class QrSigningConfigurationTests {
    final String key = Base64.getEncoder().encodeToString(QrTokenCodecTests.random(32));
    final ApplicationContextRunner runner = new ApplicationContextRunner().withBean(JsonMapper.class, () -> JsonMapper.builder().build())
            .withUserConfiguration(QrSigningConfiguration.class).withPropertyValues("spring.profiles.active=prod",
                    "loyalty.qr.issuer=loyalty-test","loyalty.qr.audience=loyalty-identity-test","loyalty.qr.active-key-id=current");
    @Test void failsClosedWithMissingKeyAndDoesNotExposeConfigurationValues() {
        runner.run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("loyalty.qr.signing-key-base64=" + key).run(context -> {
            assertThat(context).hasNotFailed(); assertThat(context.getBean(QrSigningSettings.class).toString()).isEqualTo("QrSigningSettings[redacted]");
        });
    }
    @ParameterizedTest @ValueSource(strings={"weak","!!!!", "", "c2hvcnQ="})
    void rejectsMalformedOrWeakKeysWithoutLeakingValues(String invalid) {
        runner.withPropertyValues("loyalty.qr.signing-key-base64=" + invalid).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure().getCause().getCause().getMessage()).contains("QR signing configuration");
        });
    }
    @ParameterizedTest @ValueSource(strings={"issuer","audience","active-key-id"})
    void requiresExplicitNonblankSettings(String missing) {
        runner.withPropertyValues("loyalty.qr.signing-key-base64=" + key, "loyalty.qr." + missing + "=").run(context -> assertThat(context).hasFailed());
    }
    @ParameterizedTest @ValueSource(strings={"not-json", "[]", "{\"old\":\"short\"}", "{\"old\":42}", "{\"illegal kid\":\"value\"}"})
    void rejectsInvalidVerificationRingsSafely(String ring) {
        runner.withPropertyValues("loyalty.qr.signing-key-base64=" + key,"loyalty.qr.verification-keys=" + ring)
                .run(context -> assertThat(context).hasFailed());
    }
    @Test void invalidSecretConfigurationNeverAppearsInStartupLogs(org.springframework.boot.test.system.CapturedOutput output) {
        String secret="not-base64-private-signing-material-"+java.util.UUID.randomUUID();
        runner.withPropertyValues("loyalty.qr.signing-key-base64="+secret)
                .run(context->{assertThat(context).hasFailed();assertThat(output.getAll()).doesNotContain(secret);});
        runner.withPropertyValues("loyalty.qr.signing-key-base64="+key,"loyalty.qr.verification-keys={broken:"+secret)
                .run(context->{assertThat(context).hasFailed();assertThat(output.getAll()).doesNotContain(secret,key);});
    }
    @Test void acceptsIndependentOldKeyAndRejectsAmbiguousActiveKeyOverride() {
        String previous = Base64.getEncoder().encodeToString(QrTokenCodecTests.random(32));
        runner.withPropertyValues("loyalty.qr.signing-key-base64=" + key, "loyalty.qr.verification-keys={\"old\":\"" + previous + "\"}")
                .run(context -> { assertThat(context).hasNotFailed(); assertThat(context.getBean(QrSigningSettings.class).verificationKey("old")).isNotNull(); });
        runner.withPropertyValues("loyalty.qr.signing-key-base64=" + key, "loyalty.qr.verification-keys={\"current\":\"" + previous + "\"}")
                .run(context -> assertThat(context).hasFailed());
    }
}
