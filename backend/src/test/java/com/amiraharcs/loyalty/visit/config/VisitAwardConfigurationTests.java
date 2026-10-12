package com.amiraharcs.loyalty.visit.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class VisitAwardConfigurationTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(VisitAwardConfiguration.class);
    @Test
    void defaultIsOneHundredServerOwnedPoints() {
        runner.run(context -> { assertThat(context).hasNotFailed(); assertThat(context.getBean(VisitAwardRule.class).pointsPerVisit()).isEqualTo(100); });
    }
    @ParameterizedTest
    @ValueSource(longs = {1, 250, 9007199254740991L})
    void bindsSupportedIntegerAwards(long points) {
        runner.withPropertyValues("loyalty.visits.points-per-visit=" + points).run(context -> {
            assertThat(context).hasNotFailed(); assertThat(context.getBean(VisitAwardRule.class).pointsPerVisit()).isEqualTo(points);
        });
    }
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "9007199254740992", "9223372036854775807", "1.5", "not-an-integer"})
    void rejectsInvalidAwardConfigurationAtStartup(String value) {
        runner.withPropertyValues("loyalty.visits.points-per-visit=" + value).run(context -> assertThat(context).hasFailed());
    }
}
