package com.amiraharcs.loyalty.auth.api;

import com.amiraharcs.loyalty.auth.model.User;
import com.amiraharcs.loyalty.auth.validation.SecurePassword;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

public record RegistrationRequest(@NotBlank @Email @Size(max = 254) String email,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) @NotNull @SecurePassword String password,
        @NotBlank @Size(max = 100) String displayName) {
    public RegistrationRequest {
        // Validation applies to the normalized email. Never normalize/trim a password.
        if (email != null && !email.isBlank()) { email = User.canonicalizeEmail(email); }
    }

    @Override
    public String toString() { return "RegistrationRequest[redacted]"; }
}
