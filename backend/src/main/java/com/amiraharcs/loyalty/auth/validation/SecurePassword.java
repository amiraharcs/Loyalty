package com.amiraharcs.loyalty.auth.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = SecurePasswordValidator.class)
public @interface SecurePassword {
    String message() default "Use a permitted password of 15 to 128 characters.";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
