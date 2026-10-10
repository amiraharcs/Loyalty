package com.amiraharcs.loyalty.auth.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Locale;
import java.util.Set;

/** Local starter blocklist; never transmits passwords to an external service. */
public class SecurePasswordValidator implements ConstraintValidator<SecurePassword, String> {
    private static final Set<String> COMMON = Set.of("passwordpassword", "password123456789",
            "123456789012345", "1234567890123456", "qwertyuiopasdfgh", "letmeinletmein123",
            "correcthorsebatterystaple");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) { return true; } // @NotNull supplies the required-field failure.
        int length = value.codePointCount(0, value.length());
        return length >= 15 && length <= 128 && !value.isBlank()
                && value.codePoints().noneMatch(character -> character >= 0xD800 && character <= 0xDFFF)
                && !COMMON.contains(value.toLowerCase(Locale.ROOT));
    }
}
