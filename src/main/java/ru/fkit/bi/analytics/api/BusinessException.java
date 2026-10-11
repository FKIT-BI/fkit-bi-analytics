package ru.fkit.bi.analytics.api;

import java.util.List;
import java.util.Map;

public final class BusinessException extends RuntimeException {
  private final int status;
  private final String code;
  private final List<ErrorResponse.FieldError> fields;
  private final Map<String, Object> details;

  public BusinessException(int status, String code, String message) {
    this(status, code, message, List.of(), Map.of());
  }

  public BusinessException(int status, String code, String message,
      List<ErrorResponse.FieldError> fields, Map<String, Object> details) {
    super(message);
    this.status = status;
    this.code = code;
    this.fields = List.copyOf(fields);
    this.details = Map.copyOf(details);
  }

  public int status() { return status; }
  public String code() { return code; }
  public List<ErrorResponse.FieldError> fields() { return fields; }
  public Map<String, Object> details() { return details; }
}
