package com.amiraharcs.loyalty.qr.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import java.util.UUID;

public record StaffScanRequest(@NotBlank @Size(max = 2048) @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String token,
                              @NotNull UUID storeId) {
    @Override public String toString() { return "StaffScanRequest[redacted]"; }
}
