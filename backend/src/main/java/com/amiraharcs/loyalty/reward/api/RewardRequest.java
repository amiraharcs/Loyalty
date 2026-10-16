package com.amiraharcs.loyalty.reward.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.*;
import java.math.BigInteger;
import java.time.Instant;

/** Full validated state for creation and for validation after merging a partial update. */
public record RewardRequest(@NotBlank @Size(max = 200) @Pattern(regexp = "[^\\x00]*") String name,
        @NotNull @Size(max = 2000) @Pattern(regexp = "[^\\x00]*") String description,
        @NotNull @DecimalMin("0") @DecimalMax("9007199254740991") BigInteger pointCost,
        @NotNull Boolean active, Instant availableFrom, Instant availableUntil) {
    public RewardRequest { if (name != null) { name = name.strip(); } }
    @JsonIgnore @AssertTrue(message = "Availability end must be after its start")
    public boolean isWindowValid() { return availableFrom == null || availableUntil == null || availableUntil.isAfter(availableFrom); }
    @JsonIgnore @AssertTrue(message = "Use finite UTC instants in years 0001-9999 with microsecond precision")
    public boolean isTimeSupported() { return supported(availableFrom) && supported(availableUntil); }
    private static boolean supported(Instant value) {
        return value == null || (value.getNano() % 1000 == 0
                && !value.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                && value.isBefore(Instant.parse("+10000-01-01T00:00:00Z")));
    }
}
