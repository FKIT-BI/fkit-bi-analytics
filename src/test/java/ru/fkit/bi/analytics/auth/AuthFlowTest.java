package ru.fkit.bi.analytics.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import ru.fkit.bi.analytics.AnalyticsApplication;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(AuthFlowTest.TestEndpointConfiguration.class)
class AuthFlowTest {
  private static final String PASSWORD = "safe-passphrase-123";
  @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("fkit.auth.jwt-secret", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
    registry.add("fkit.auth.trusted-proxy-cidrs", () -> "192.0.2.0/24");
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder passwords;
  @Autowired JwtDecoder decoder;
  @Autowired JwtEncoder encoder;
  @Autowired ObjectMapper mapper;
  @Autowired PlatformTransactionManager transactionManager;

  @BeforeEach void clearUsers() { jdbc.update("DELETE FROM identity.users"); }

  @Test void bootstrapIsIdempotentAndKeepsOriginalCredentials() {
    BootstrapUserCommand bootstrapCommand = new BootstrapUserCommand(jdbc, passwords, new TransactionTemplate(transactionManager));
    org.junit.jupiter.api.Assertions.assertTrue(bootstrapCommand.provision("Root.User", "Root operator", PASSWORD));
    String firstHash = jdbc.queryForObject("SELECT password_hash FROM identity.users WHERE is_bootstrap", String.class);
    org.junit.jupiter.api.Assertions.assertFalse(bootstrapCommand.provision("another", "Other", "another-password-123"));
    org.junit.jupiter.api.Assertions.assertEquals(firstHash,
        jdbc.queryForObject("SELECT password_hash FROM identity.users WHERE is_bootstrap", String.class));
    org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM identity.users", Integer.class));
    org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,
        () -> jdbc.update("UPDATE identity.users SET is_bootstrap = FALSE WHERE username = 'root.user'"));
  }

  @Test void bootstrapApplicationRunnerReadsCredentialsAndNoOpsOnRepeat() {
    Map<String, String> secrets = Map.of(
        "FKIT_BOOTSTRAP_USERNAME", "Root.User",
        "FKIT_BOOTSTRAP_DISPLAY_NAME", "Root operator",
        "FKIT_BOOTSTRAP_PASSWORD", PASSWORD);
    BootstrapUserCommand command = new BootstrapUserCommand(jdbc, passwords,
        new TransactionTemplate(transactionManager), secrets::get);
    command.run(new DefaultApplicationArguments(new String[0]));
    String originalHash = jdbc.queryForObject("SELECT password_hash FROM identity.users WHERE is_bootstrap", String.class);
    command.run(new DefaultApplicationArguments(new String[0]));
    org.junit.jupiter.api.Assertions.assertEquals(1,
        jdbc.queryForObject("SELECT count(*) FROM identity.users", Integer.class));
    org.junit.jupiter.api.Assertions.assertEquals(originalHash,
        jdbc.queryForObject("SELECT password_hash FROM identity.users WHERE is_bootstrap", String.class));
  }

  @Test void bootstrapCliDispatchesAndExitsIdempotently() {
    String[] args = {
        "bootstrap-user",
        "--spring.datasource.url=" + postgres.getJdbcUrl(),
        "--spring.datasource.username=" + postgres.getUsername(),
        "--spring.datasource.password=" + postgres.getPassword(),
        "--fkit.auth.jwt-secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "--fkit.bootstrap.username=Cli.Root",
        "--fkit.bootstrap.display-name=CLI operator",
        "--fkit.bootstrap.password=" + PASSWORD,
        "--spring.main.banner-mode=off"};
    AnalyticsApplication.main(args);
    String originalHash = jdbc.queryForObject("SELECT password_hash FROM identity.users WHERE is_bootstrap", String.class);
    AnalyticsApplication.main(args);
    org.junit.jupiter.api.Assertions.assertEquals(1,
        jdbc.queryForObject("SELECT count(*) FROM identity.users", Integer.class));
    org.junit.jupiter.api.Assertions.assertEquals(originalHash,
        jdbc.queryForObject("SELECT password_hash FROM identity.users WHERE is_bootstrap", String.class));
  }

  @Test void loginReturnsJwtAndCredentialFailuresAreIndistinguishable() throws Exception {
    UUID bootstrapId = addUser("operator", "Operator", PASSWORD, true);
    String unknown = mvc.perform(post("/api/v1/auth/login").with(remoteAddr("192.0.2.10"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"missing\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
        .andReturn().getResponse().getContentAsString();
    String wrongPassword = mvc.perform(post("/api/v1/auth/login").with(remoteAddr("192.0.2.11"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"operator\",\"password\":\"wrong-password-123\"}"))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
        .andReturn().getResponse().getContentAsString();
    org.junit.jupiter.api.Assertions.assertEquals(
        mapper.readTree(unknown).path("message"), mapper.readTree(wrongPassword).path("message"));

    String response = mvc.perform(post("/api/v1/auth/login").with(remoteAddr("192.0.2.12"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"OPERATOR\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.tokenType").value("Bearer"))
        .andExpect(jsonPath("$.expiresIn").value(1800)).andExpect(jsonPath("$.user.canCreateUsers").value(true))
        .andReturn().getResponse().getContentAsString();
    String token = mapper.readTree(response).path("accessToken").asText();
    var jwt = decoder.decode(token);
    org.junit.jupiter.api.Assertions.assertEquals(bootstrapId.toString(), jwt.getSubject());
    org.junit.jupiter.api.Assertions.assertEquals("fkit-bi", jwt.getClaimAsString("iss"));
    org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("fkit-bi-api"), jwt.getAudience());
    org.junit.jupiter.api.Assertions.assertEquals(1800, jwt.getExpiresAt().getEpochSecond() - jwt.getIssuedAt().getEpochSecond());
    org.junit.jupiter.api.Assertions.assertFalse(jwt.getClaims().containsKey("role"));
    mvc.perform(get("/__test/business").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
  }

  @Test void loginFieldValidationUsesContractBadRequestStatus() throws Exception {
    mvc.perform(post("/api/v1/auth/login").with(remoteAddr("192.0.2.31"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"x\",\"password\":\"short\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
        .andExpect(jsonPath("$.fieldErrors").isArray());
  }

  @Test void authTransportRejectsMalformedJsonAndUtf8PasswordsOverTheBcryptLimit() throws Exception {
    String malformedBody = mvc.perform(post("/api/v1/auth/login").with(remoteAddr("192.0.2.32"))
            .header("X-Trace-Id", "malformed-auth-body")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"operator\","))
        .andExpect(status().isBadRequest())
        .andExpect(header().string("X-Trace-Id", "malformed-auth-body"))
        .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
        .andExpect(jsonPath("$.traceId").value("malformed-auth-body"))
        .andReturn().getResponse().getContentAsString();
    org.junit.jupiter.api.Assertions.assertFalse(malformedBody.contains("operator"));

    UUID bootstrapId = addUser("root", "Root", PASSWORD, true);
    String accessToken = token(bootstrapId, "fkit-bi", "fkit-bi-api", Instant.now().plusSeconds(1800));
    String overlongUtf8Password = "я".repeat(37);
    String createPayload = mapper.writeValueAsString(Map.of(
        "username", "utf8.user",
        "displayName", "UTF-8 user",
        "initialPassword", overlongUtf8Password));
    String validationBody = mvc.perform(post("/api/v1/auth/users")
            .header("Authorization", "Bearer " + accessToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(createPayload))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.fieldErrors[0].path").value("initialPassword"))
        .andReturn().getResponse().getContentAsString();
    org.junit.jupiter.api.Assertions.assertFalse(validationBody.contains(overlongUtf8Password));
    org.junit.jupiter.api.Assertions.assertEquals(0,
        jdbc.queryForObject("SELECT count(*) FROM identity.users WHERE username = 'utf8.user'", Integer.class));
  }

  @Test void authenticationFailureUsesTheCommonCorrelatedErrorEnvelope() throws Exception {
    mvc.perform(get("/__test/business").header("X-Trace-Id", "anonymous-business-request"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("X-Trace-Id", "anonymous-business-request"))
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
        .andExpect(jsonPath("$.message").value("Authentication failed"))
        .andExpect(jsonPath("$.traceId").value("anonymous-business-request"))
        .andExpect(jsonPath("$.fieldErrors").isArray())
        .andExpect(jsonPath("$.details").isMap());
  }

  @Test void onlyBootstrapCanCreateUsersAndResponseNeverContainsHash() throws Exception {
    UUID bootstrapId = addUser("root", "Root", PASSWORD, true);
    addUser("normal", "Normal", PASSWORD, false);
    String bootstrapToken = token(bootstrapId, "fkit-bi", "fkit-bi-api", Instant.now().plusSeconds(1800));
    String normalToken = token(jdbc.queryForObject("SELECT id FROM identity.users WHERE username='normal'", UUID.class),
        "fkit-bi", "fkit-bi-api", Instant.now().plusSeconds(1800));
    String body = "{\"username\":\"New.User\",\"displayName\":\"New user\",\"initialPassword\":\"" + PASSWORD + "\"}";
    var created = mvc.perform(post("/api/v1/auth/users").header("Authorization", "Bearer " + bootstrapToken)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated()).andExpect(header().exists("Location"))
        .andExpect(jsonPath("$.username").value("new.user"))
        .andExpect(jsonPath("$.password_hash").doesNotExist())
        .andReturn();
    org.junit.jupiter.api.Assertions.assertFalse(created.getResponse().getContentAsString().contains("password_hash"));
    mvc.perform(post("/api/v1/auth/users").header("Authorization", "Bearer " + bootstrapToken)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
    mvc.perform(post("/api/v1/auth/users").header("Authorization", "Bearer " + normalToken)
            .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"second\",\"displayName\":\"Second\",\"initialPassword\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/auth/users").header("Authorization", "Bearer " + bootstrapToken)
            .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"x\",\"displayName\":\"\",\"initialPassword\":\"short\"}"))
        .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.fieldErrors").isArray());
  }

  @Test void missingInvalidExpiredIssuerAndAudienceTokensAreRejectedWhileProbesStayOpen() throws Exception {
    mvc.perform(get("/__test/business")).andExpect(status().isUnauthorized());
    UUID id = UUID.randomUUID();
    for (String token : new String[] {"bad.token.value", token(id, "other", "fkit-bi-api", Instant.now().plusSeconds(60)),
        token(id, "fkit-bi", "other-api", Instant.now().plusSeconds(60)),
        token(id, "fkit-bi", "fkit-bi-api", Instant.now().minusSeconds(1)),
        tokenWithLifetime(id, Instant.now().plusSeconds(60), 1800),
        tokenWithLifetime(id, Instant.now(), 1801)}) {
      mvc.perform(get("/__test/business").header("Authorization", "Bearer " + token))
          .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
    }
    mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
  }

  @Test void loginLimiterReturnsRetryAfterAndDoesNotTrustUnconfiguredForwardingHeaders() throws Exception {
    for (int attempt = 1; attempt <= 11; attempt++) {
      var result = mvc.perform(post("/api/v1/auth/login").with(remoteAddr("203.0.113.88"))
              .header("X-Forwarded-For", "198.51.100." + attempt)
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"username\":\"missing\",\"password\":\"" + PASSWORD + "\"}"));
      if (attempt < 11) result.andExpect(status().isUnauthorized());
      else result.andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }
  }

  @Test void loginLimiterUsesForwardedClientAddressFromConfiguredTrustedProxy() throws Exception {
    for (int attempt = 1; attempt <= 20; attempt++) {
      String clientAddress = attempt % 2 == 0 ? "198.51.100.42" : "198.51.100.43";
      mvc.perform(post("/api/v1/auth/login").with(remoteAddr("192.0.2.254"))
              .header("X-Forwarded-For", clientAddress)
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"username\":\"missing\",\"password\":\"" + PASSWORD + "\"}"))
          .andExpect(status().isUnauthorized());
    }
    boolean limited = false;
    for (int attempt = 1; attempt <= 21 && !limited; attempt++) {
      var response = mvc.perform(post("/api/v1/auth/login").with(remoteAddr("192.0.2.254"))
              .header("X-Forwarded-For", "198.51.100.42")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"username\":\"missing\",\"password\":\"" + PASSWORD + "\"}"))
          .andReturn().getResponse();
      if (response.getStatus() == 429) {
        org.junit.jupiter.api.Assertions.assertNotNull(response.getHeader("Retry-After"));
        limited = true;
      } else {
        org.junit.jupiter.api.Assertions.assertEquals(401, response.getStatus());
      }
    }
    org.junit.jupiter.api.Assertions.assertTrue(limited,
        "Trusted forwarded client must reach its own fixed-window limit");
  }

  @Test void bootstrapUserCreationLimiterAllowsFiveAndThenReturnsRetryAfter() throws Exception {
    UUID bootstrapId = addUser("root", "Root", PASSWORD, true);
    String accessToken = token(bootstrapId, "fkit-bi", "fkit-bi-api", Instant.now().plusSeconds(1800));
    for (int attempt = 1; attempt <= 6; attempt++) {
      String body = "{\"username\":\"user" + attempt + "\",\"displayName\":\"User " + attempt
          + "\",\"initialPassword\":\"" + PASSWORD + "\"}";
      var result = mvc.perform(post("/api/v1/auth/users").header("Authorization", "Bearer " + accessToken)
          .contentType(MediaType.APPLICATION_JSON).content(body));
      if (attempt <= 5) result.andExpect(status().isCreated());
      else result.andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }
  }

  private UUID addUser(String username, String displayName, String password, boolean bootstrap) {
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO identity.users (id, username, display_name, password_hash, is_bootstrap, created_at) VALUES (?, ?, ?, ?, ?, ?)",
        id, username, displayName, passwords.encode(password), bootstrap, java.sql.Timestamp.from(Instant.now()));
    return id;
  }

  private String token(UUID subject, String issuer, String audience, Instant expiresAt) {
    Instant now = expiresAt.isAfter(Instant.now()) ? Instant.now() : expiresAt.minusSeconds(60);
    return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
        JwtClaimsSet.builder().issuer(issuer).audience(java.util.List.of(audience)).subject(subject.toString())
            .issuedAt(now).expiresAt(expiresAt).build())).getTokenValue();
  }

  private String tokenWithLifetime(UUID subject, Instant issuedAt, long lifetimeSeconds) {
    return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
        JwtClaimsSet.builder().issuer("fkit-bi").audience(java.util.List.of("fkit-bi-api"))
            .subject(subject.toString()).issuedAt(issuedAt).expiresAt(issuedAt.plusSeconds(lifetimeSeconds)).build())).getTokenValue();
  }

  private static RequestPostProcessor remoteAddr(String address) {
    return request -> { request.setRemoteAddr(address); return request; };
  }

  @TestConfiguration static class TestEndpointConfiguration {
    @Bean BusinessApi businessApi() { return new BusinessApi(); }
  }
  @RestController @RequestMapping("/__test") static class BusinessApi {
    @GetMapping("/business") String business() { return "ok"; }
  }
}
