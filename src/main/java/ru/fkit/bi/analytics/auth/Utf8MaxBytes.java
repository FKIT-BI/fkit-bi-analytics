package ru.fkit.bi.analytics.auth;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Constraint(validatedBy = Utf8MaxBytesValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface Utf8MaxBytes {
  String message() default "Value exceeds the allowed UTF-8 byte length";
  Class<?>[] groups() default {};
  Class<? extends Payload>[] payload() default {};
  int value();
}
