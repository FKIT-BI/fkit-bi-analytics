package ru.fkit.bi.analytics.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class AuthDtos {
  private AuthDtos() {}

  @JsonIgnoreProperties(ignoreUnknown = false)
  public record LoginRequest(
      @NotBlank @Size(min = 3, max = 64) @Pattern(regexp = "^[A-Za-z0-9._-]+$") String username,
      @NotBlank @Size(min = 12, max = 72) @Utf8MaxBytes(72) String password) {}

  @JsonIgnoreProperties(ignoreUnknown = false)
  public record CreateUserRequest(
      @NotBlank @Size(min = 3, max = 64) @Pattern(regexp = "^[A-Za-z0-9._-]+$") String username,
      @NotBlank @Size(min = 1, max = 100) String displayName,
      @NotBlank @Size(min = 12, max = 72) @Utf8MaxBytes(72) String initialPassword) {}

  public record User(UUID id, String username, String displayName, Instant createdAt) {}
  public record AuthenticatedUser(UUID id, String username, String displayName, boolean canCreateUsers) {}
  public record LoginResponse(String accessToken, String tokenType, int expiresIn, AuthenticatedUser user) {}
}
