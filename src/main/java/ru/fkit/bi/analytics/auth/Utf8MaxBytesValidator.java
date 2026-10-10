package ru.fkit.bi.analytics.auth;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.nio.charset.StandardCharsets;

public class Utf8MaxBytesValidator implements ConstraintValidator<Utf8MaxBytes, String> {
  private int maxBytes;
  @Override public void initialize(Utf8MaxBytes constraint) { maxBytes = constraint.value(); }
  @Override public boolean isValid(String value, ConstraintValidatorContext context) {
    return value == null || value.getBytes(StandardCharsets.UTF_8).length <= maxBytes;
  }
}
