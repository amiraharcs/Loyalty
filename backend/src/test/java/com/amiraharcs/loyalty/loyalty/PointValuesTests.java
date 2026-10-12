package com.amiraharcs.loyalty.loyalty;

import com.amiraharcs.loyalty.loyalty.model.*;
import com.amiraharcs.loyalty.auth.model.User;
import java.math.BigInteger;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class PointValuesTests {
    @ParameterizedTest
    @ValueSource(longs = {0, 1, -1, 9007199254740991L, -9007199254740991L, 4000000000L})
    void projectsSupportedIntegersExactly(long value) {
        assertThat(PointValues.safeInteger(BigInteger.valueOf(value))).isEqualTo(value);
    }
    @ParameterizedTest
    @ValueSource(longs = {9007199254740992L, -9007199254740992L, Long.MAX_VALUE, Long.MIN_VALUE})
    void rejectsUnsafeProjectionAndOverflowBeforeMutation(long value) {
        assertThatThrownBy(() -> PointValues.safeInteger(BigInteger.valueOf(value))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PointValues.amount(value)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void modelRejectsWrongSignsAndMissingAdjustmentAttribution() {
        var account = org.mockito.Mockito.mock(LoyaltyAccount.class);
        var actor = org.mockito.Mockito.mock(User.class);
        for (var type : new PointTransactionType[]{PointTransactionType.EARN, PointTransactionType.REFUND}) {
            assertThatThrownBy(() -> new PointTransaction(account, -1, type, null, null, Instant.now()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new PointTransaction(account, 1, PointTransactionType.REDEMPTION, null, null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PointTransaction(account, 1, PointTransactionType.ADJUSTMENT, null, "Reason", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PointTransaction(account, 1, PointTransactionType.ADJUSTMENT, actor, "  ", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PointTransaction(account, 1, PointTransactionType.ADJUSTMENT, actor, "x".repeat(1001), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void totalCheckUsesExactIntegerArithmetic() {
        assertThat(PointValues.checkedTotal(PointValues.MAX_SAFE_INTEGER, -1)).isEqualTo(PointValues.MAX_SAFE_INTEGER - 1);
        assertThatThrownBy(() -> PointValues.checkedTotal(PointValues.MAX_SAFE_INTEGER, 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PointValues.checkedTotal(Long.MAX_VALUE, Long.MAX_VALUE)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PointValues.safeInteger(BigInteger.TEN.pow(100))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PointValues.amount(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
