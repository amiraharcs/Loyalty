package com.amiraharcs.loyalty.auth.api;

import com.amiraharcs.loyalty.auth.model.User;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

/** Login verifies existing credentials; registration's common-password policy is not reapplied. */
public record LoginRequest(@NotBlank @Email @Size(max = 254) String email,
        @NotNull @Size(min = 1, max = 256) @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String password) {
    public LoginRequest { if (email != null && !email.isBlank()) { email = User.canonicalizeEmail(email); } }
    @Override public String toString() { return "LoginRequest[redacted]"; }
}
