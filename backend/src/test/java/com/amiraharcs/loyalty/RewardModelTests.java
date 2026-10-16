package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.reward.model.Reward;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class RewardModelTests {
    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    @ParameterizedTest
    @CsvSource(value={"true,null,null,true", "false,null,null,false", "true,-1,1,true", "true,0,1,true", "true,1,2,false", "true,-2,0,false", "true,-2,-1,false", "true,null,0,false", "true,0,null,true"},nullValues="null")
    void activeAndOptionalUtcBoundsControlAvailability(boolean active, Integer start, Integer end, boolean expected) {
        var reward = new Reward("Coffee","Description",3000,active,start==null?null:NOW.plusSeconds(start),end==null?null:NOW.plusSeconds(end),NOW);
        assertThat(reward.isAvailableAt(NOW)).isEqualTo(expected);
    }
    @Test void costIsAnExactNonnegativeSafeIntegerAndTimeWindowMustHavePositiveLength() {
        assertThat(new Reward("Coffee","",0,true,null,null,NOW).getPointCost()).isZero();
        assertThat(new Reward("Coffee","",9007199254740991L,true,null,null,NOW).getPointCost()).isEqualTo(9007199254740991L);
        for (long cost : new long[]{-1,9007199254740992L}) {
            assertThatThrownBy(() -> new Reward("Coffee","",cost,true,null,null,NOW)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new Reward("Coffee","",1,true,NOW,NOW,NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Reward("Coffee","",1,true,NOW,NOW.minusSeconds(1),NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Reward(" ","",1,true,null,null,NOW)).isInstanceOf(IllegalArgumentException.class);
    }
}
