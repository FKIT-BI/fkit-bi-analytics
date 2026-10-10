package ru.fkit.bi.analytics.auth;

import java.time.Instant;

public final class AuthError extends RuntimeException {
  private final int status;
  private final String code;
  private final long retryAfter;

  private AuthError(int status, String code, String message, long retryAfter) {
    super(message);
    this.status = status;
    this.code = code;
    this.retryAfter = retryAfter;
  }

  public static AuthError forbidden() { return new AuthError(403, "FORBIDDEN", "User cannot create accounts", 0); }
  public static AuthError conflict() { return new AuthError(409, "USERNAME_TAKEN", "Username is already in use", 0); }
  public static AuthError rateLimited(long retryAfter) { return new AuthError(429, "TOO_MANY_REQUESTS", "Request limit exceeded", retryAfter); }
  public int status() { return status; }
  public String code() { return code; }
  public long retryAfter() { return retryAfter; }
}
