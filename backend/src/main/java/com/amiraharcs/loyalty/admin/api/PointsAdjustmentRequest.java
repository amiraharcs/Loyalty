package com.amiraharcs.loyalty.admin.api;

import jakarta.validation.constraints.*;
import java.math.BigInteger;

/** BigInteger prevents decimal truncation and preserves exact range validation. */
public record PointsAdjustmentRequest(@NotNull @DecimalMin("-9007199254740991") @DecimalMax("9007199254740991") BigInteger signedAmount,
        @NotBlank @Size(max = 1000) String reason) {
    public PointsAdjustmentRequest { if (reason != null) { reason = reason.strip(); } }
    @com.fasterxml.jackson.annotation.JsonIgnore
    @AssertTrue(message = "Amount must be nonzero")
    public boolean isAmountNonzero() { return signedAmount == null || signedAmount.signum() != 0; }
}
