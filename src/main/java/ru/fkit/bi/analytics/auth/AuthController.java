package ru.fkit.bi.analytics.auth;

import static ru.fkit.bi.analytics.auth.AuthDtos.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Clock;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final AuthService authService;
  private final RateLimiter rateLimiter;
  private final TrustedClientAddress addresses;
  private final Clock clock;

  @Autowired
  public AuthController(AuthService authService, RateLimiter rateLimiter, TrustedClientAddress addresses) {
    this(authService, rateLimiter, addresses, Clock.systemUTC());
  }

  AuthController(AuthService authService, RateLimiter rateLimiter, TrustedClientAddress addresses, Clock clock) {
    this.authService = authService;
    this.rateLimiter = rateLimiter;
    this.addresses = addresses;
    this.clock = clock;
  }

  @PostMapping("/login")
  public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
    rejectIfLimited("login:" + addresses.resolve(servletRequest), 10);
    return authService.login(request);
  }

  @PostMapping("/users")
  public ResponseEntity<User> createUser(@AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody CreateUserRequest request) {
    UUID actorId;
    try { actorId = UUID.fromString(jwt.getSubject()); }
    catch (RuntimeException invalidSubject) { throw new org.springframework.security.authentication.BadCredentialsException("Authentication failed"); }
    rejectIfLimited("create:" + actorId, 5);
    User created = authService.createUser(actorId, request);
    return ResponseEntity.created(URI.create("/api/v1/auth/users/" + created.id())).body(created);
  }

  private void rejectIfLimited(String key, int limit) {
    long retryAfter = rateLimiter.acquire(key, limit, clock.millis());
    if (retryAfter > 0) throw AuthError.rateLimited(retryAfter);
  }
}
