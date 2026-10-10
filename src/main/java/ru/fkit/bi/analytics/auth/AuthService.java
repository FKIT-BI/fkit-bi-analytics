package ru.fkit.bi.analytics.auth;

import static ru.fkit.bi.analytics.auth.AuthDtos.*;

import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
  private static final String DUMMY_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7pvh8uKTVdK6Cj6B9N6bB6e";
  private final JdbcTemplate jdbc;
  private final PasswordEncoder passwords;
  private final JwtEncoder jwtEncoder;

  public AuthService(JdbcTemplate jdbc, PasswordEncoder passwords, JwtEncoder jwtEncoder) {
    this.jdbc = jdbc;
    this.passwords = passwords;
    this.jwtEncoder = jwtEncoder;
  }

  public LoginResponse login(LoginRequest request) {
    UserRow user = jdbc.query("SELECT id, username, display_name, password_hash, is_bootstrap FROM identity.users WHERE username = ?",
        result -> result.next() ? new UserRow(result.getObject("id", UUID.class), result.getString("username"),
            result.getString("display_name"), result.getString("password_hash"), result.getBoolean("is_bootstrap")) : null,
        request.username().toLowerCase(java.util.Locale.ROOT));
    String passwordHash = user == null ? DUMMY_HASH : user.passwordHash();
    if (!passwords.matches(request.password(), passwordHash) || user == null) {
      throw new org.springframework.security.authentication.BadCredentialsException("Authentication failed");
    }
    Instant now = Instant.now();
    JwtClaimsSet claims = JwtClaimsSet.builder()
        .issuer("fkit-bi")
        .audience(java.util.List.of("fkit-bi-api"))
        .subject(user.id().toString())
        .issuedAt(now)
        .expiresAt(now.plusSeconds(1800))
        .build();
    String token = jwtEncoder.encode(JwtEncoderParameters.from(
        JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    return new LoginResponse(token, "Bearer", 1800,
        new AuthenticatedUser(user.id(), user.username(), user.displayName(), user.bootstrap()));
  }

  public User createUser(UUID actorId, CreateUserRequest request) {
    final Boolean allowed;
    try {
      allowed = jdbc.queryForObject("SELECT is_bootstrap FROM identity.users WHERE id = ?", Boolean.class, actorId);
    } catch (EmptyResultDataAccessException missingActor) {
      throw AuthError.forbidden();
    }
    if (!Boolean.TRUE.equals(allowed)) throw AuthError.forbidden();
    UUID id = UUID.randomUUID();
    String username = request.username().toLowerCase(java.util.Locale.ROOT);
    Instant createdAt = Instant.now();
    try {
      jdbc.update("INSERT INTO identity.users (id, username, display_name, password_hash, is_bootstrap, created_at) "
              + "VALUES (?, ?, ?, ?, FALSE, ?)", id, username, request.displayName().trim(),
          passwords.encode(request.initialPassword()), java.sql.Timestamp.from(createdAt));
    } catch (DuplicateKeyException duplicate) {
      throw AuthError.conflict();
    }
    return new User(id, username, request.displayName().trim(), createdAt);
  }

  private record UserRow(UUID id, String username, String displayName, String passwordHash, boolean bootstrap) {}
}
