package ru.fkit.bi.analytics.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.core.MethodParameter;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.bind.annotation.CookieValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.fkit.bi.analytics.auth.AuthError;

@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);
  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ErrorResponse> malformedJson(HttpServletRequest request) {
    return error(request, HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Тело запроса содержит ошибку", List.of());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
    List<ErrorResponse.FieldError> fields = exception.getBindingResult().getFieldErrors().stream()
        .map(error -> constraintError(error.getField(), error.getCode()))
        .toList();
    if ("/api/v1/auth/login".equals(request.getRequestURI())) {
      return error(request, HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Проверьте поля запроса", fields);
    }
    return error(request, HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "Проверьте поля запроса", fields);
  }

  @ExceptionHandler(AuthError.class)
  ResponseEntity<ErrorResponse> authError(AuthError exception, HttpServletRequest request) {
    ResponseEntity.BodyBuilder response = ResponseEntity.status(exception.status());
    if (exception.status() == 429) response.header("Retry-After", Long.toString(exception.retryAfter()));
    return response.body(errorBody(request, exception.code(), exception.getMessage()));
  }

  @ExceptionHandler(BusinessException.class)
  ResponseEntity<ErrorResponse> businessError(BusinessException exception, HttpServletRequest request) {
    ErrorResponse body = errorBody(request, exception.code(), exception.getMessage());
    return ResponseEntity.status(exception.status()).body(new ErrorResponse(body.code(), body.message(), body.traceId(),
        exception.fields(), exception.details()));
  }

  @ExceptionHandler(org.springframework.security.authentication.BadCredentialsException.class)
  ResponseEntity<ErrorResponse> authenticationFailed(HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(errorBody(request, "AUTHENTICATION_FAILED", "Authentication failed"));
  }

  @ExceptionHandler(HandlerMethodValidationException.class)
  ResponseEntity<ru.fkit.bi.analytics.api.ErrorResponse> methodValidation(
      HandlerMethodValidationException exception, HttpServletRequest request) {
    List<ru.fkit.bi.analytics.api.ErrorResponse.FieldError> fields = exception.getParameterValidationResults().stream()
        .flatMap(result -> result.getResolvableErrors().stream().map(error ->
            constraintError(parameterPath(result.getMethodParameter()), error.getCodes() == null || error.getCodes().length == 0 ? null : error.getCodes()[error.getCodes().length - 1])))
        .distinct()
        .toList();
    return error(request, HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "Проверьте параметры запроса", fields);
  }

  @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
  ResponseEntity<ErrorResponse> typeMismatch(
      org.springframework.web.method.annotation.MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
    return error(request, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Проверьте параметры запроса",
        List.of(new ErrorResponse.FieldError(parameterPath(exception.getParameter()), "INVALID_FORMAT", "Значение имеет неверный формат")));
  }

  @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
  ResponseEntity<ErrorResponse> missingParameter(
      org.springframework.web.bind.MissingServletRequestParameterException exception, HttpServletRequest request) {
    return error(request, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Проверьте параметры запроса",
        List.of(new ErrorResponse.FieldError(exception.getParameterName(), "REQUIRED", "Обязательный параметр")));
  }

  /** Maps Bean Validation constraint names to stable field codes and safe Russian text. */
  static ErrorResponse.FieldError constraintError(String path, String constraint) {
    String name = constraint == null ? "" : constraint;
    return switch (name) {
      case "NotNull", "NotBlank", "NotEmpty" -> new ErrorResponse.FieldError(path, "REQUIRED", "Обязательное поле");
      case "Size" -> new ErrorResponse.FieldError(path, "INVALID_LENGTH", "Недопустимая длина значения");
      case "Pattern", "Email" -> new ErrorResponse.FieldError(path, "INVALID_FORMAT", "Значение имеет неверный формат");
      case "Min", "Max", "DecimalMin", "DecimalMax", "Positive", "PositiveOrZero", "Negative", "NegativeOrZero" ->
          new ErrorResponse.FieldError(path, "OUT_OF_RANGE", "Значение вне допустимого диапазона");
      default -> new ErrorResponse.FieldError(path, "INVALID", "Недопустимое значение");
    };
  }

  private ResponseEntity<ru.fkit.bi.analytics.api.ErrorResponse> frameworkError(
      org.springframework.web.ErrorResponse exception, HttpServletRequest request) {
    HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
    String code = switch (status) {
      case BAD_REQUEST -> "MALFORMED_REQUEST";
      case NOT_FOUND -> "NOT_FOUND";
      case METHOD_NOT_ALLOWED -> "METHOD_NOT_ALLOWED";
      case CONFLICT -> "CONFLICT";
      default -> "HTTP_" + status.value() + "_" + status.name();
    };
    String message = switch (status) {
      case BAD_REQUEST -> "Некорректный запрос";
      case NOT_FOUND -> "Ресурс не найден";
      case METHOD_NOT_ALLOWED -> "Метод не поддерживается";
      case CONFLICT -> "Конфликт состояния ресурса";
      case UNSUPPORTED_MEDIA_TYPE -> "Неподдерживаемый формат запроса";
      default -> "Запрос не может быть обработан";
    };
    return error(request, status, code, message, List.of());
  }

  private String parameterPath(MethodParameter parameter) {
    RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
    if (requestParam != null) return annotationName(requestParam.name(), requestParam.value(), parameter);
    PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
    if (pathVariable != null) return annotationName(pathVariable.name(), pathVariable.value(), parameter);
    RequestHeader requestHeader = parameter.getParameterAnnotation(RequestHeader.class);
    if (requestHeader != null) return annotationName(requestHeader.name(), requestHeader.value(), parameter);
    CookieValue cookieValue = parameter.getParameterAnnotation(CookieValue.class);
    if (cookieValue != null) return annotationName(cookieValue.name(), cookieValue.value(), parameter);
    return parameter.getParameterName() == null ? "parameter" : parameter.getParameterName();
  }

  private String annotationName(String name, String value, MethodParameter parameter) {
    if (!name.isBlank()) return name;
    if (!value.isBlank()) return value;
    return parameter.getParameterName() == null ? "parameter" : parameter.getParameterName();
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorResponse> unexpected(Exception exception, HttpServletRequest request) {
    if (exception instanceof org.springframework.web.ErrorResponse frameworkError) {
      return frameworkError(frameworkError, request);
    }
    ResponseEntity<ErrorResponse> response = error(
        request, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Внутренняя ошибка сервера", List.of());
    LOGGER.error("event=api_error code={} traceId={}", response.getBody().code(), response.getBody().traceId(), exception);
    return response;
  }

  private ResponseEntity<ErrorResponse> error(
      HttpServletRequest request, HttpStatus status, String code, String message, List<ErrorResponse.FieldError> fields) {
    ErrorResponse body = errorBody(request, code, message);
    return ResponseEntity.status(status).body(new ErrorResponse(body.code(), body.message(), body.traceId(), fields, body.details()));
  }

  private ErrorResponse errorBody(HttpServletRequest request, String code, String message) {
    Object attribute = request.getAttribute(TraceIdFilter.ATTRIBUTE);
    String traceId = attribute instanceof String value ? value : "unknown";
    return new ErrorResponse(code, message, traceId, List.of(), Map.of());
  }
}
