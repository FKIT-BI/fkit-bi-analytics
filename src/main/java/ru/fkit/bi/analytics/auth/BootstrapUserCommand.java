package ru.fkit.bi.analytics.auth;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name = "fkit.bootstrap.command-enabled", havingValue = "true")
public class BootstrapUserCommand implements ApplicationRunner {
  private static final Logger LOGGER = LoggerFactory.getLogger(BootstrapUserCommand.class);
  private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9._-]{3,64}$");
  private final JdbcTemplate jdbc;
  private final PasswordEncoder passwordEncoder;
  private final TransactionTemplate transactions;
  private final Function<String, String> environment;
  private final boolean resetOnly;

  @Autowired
  public BootstrapUserCommand(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, TransactionTemplate transactions,
      Environment environment) {
    this(jdbc, passwordEncoder, transactions, name -> environment.getProperty("fkit.bootstrap." + name
        .substring("FKIT_BOOTSTRAP_".length()).toLowerCase(Locale.ROOT).replace('_', '-')),
        environment.getProperty("fkit.bootstrap.reset-only", Boolean.class, false));
  }

  BootstrapUserCommand(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, TransactionTemplate transactions) {
    this(jdbc, passwordEncoder, transactions, System::getenv, false);
  }

  BootstrapUserCommand(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, TransactionTemplate transactions,
      Function<String, String> environment) {
    this(jdbc, passwordEncoder, transactions, environment, false);
  }

  BootstrapUserCommand(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, TransactionTemplate transactions,
      Function<String, String> environment, boolean resetOnly) {
    this.jdbc = jdbc;
    this.passwordEncoder = passwordEncoder;
    this.transactions = transactions;
    this.environment = environment;
    this.resetOnly = resetOnly;
  }

  @Override public void run(ApplicationArguments args) {
    if (resetOnly) {
      resetExistingBootstrap(required("FKIT_BOOTSTRAP_USERNAME"), required("FKIT_BOOTSTRAP_PASSWORD"));
      return;
    }
    Integer count = jdbc.queryForObject("SELECT count(*) FROM identity.users", Integer.class);
    if (count != null && count > 0) {
      LOGGER.info("event=bootstrap_user already_initialized=true");
      return;
    }
    provision(required("FKIT_BOOTSTRAP_USERNAME"), required("FKIT_BOOTSTRAP_DISPLAY_NAME"), required("FKIT_BOOTSTRAP_PASSWORD"));
  }

  boolean provision(String suppliedUsername, String suppliedDisplayName, String password) {
    boolean[] created = {false};
    transactions.executeWithoutResult(status -> {
      jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
        try (java.sql.Statement statement = connection.createStatement()) {
          statement.execute("SELECT pg_advisory_xact_lock(1329748801)");
        }
        return null;
      });
      Integer count = jdbc.queryForObject("SELECT count(*) FROM identity.users", Integer.class);
      if (count != null && count > 0) {
        LOGGER.info("event=bootstrap_user already_initialized=true");
        return;
      }
      String username = suppliedUsername;
      String displayName = suppliedDisplayName.trim();
      if (!USERNAME.matcher(username).matches()) throw new IllegalArgumentException("FKIT_BOOTSTRAP_USERNAME is invalid");
      if (displayName.isBlank() || displayName.length() > 100) throw new IllegalArgumentException("FKIT_BOOTSTRAP_DISPLAY_NAME is invalid");
      validatePassword(password);
      jdbc.update("INSERT INTO identity.users (id, username, display_name, password_hash, is_bootstrap) VALUES (?, ?, ?, ?, TRUE)",
          UUID.randomUUID(), username.toLowerCase(Locale.ROOT), displayName, passwordEncoder.encode(password));
      created[0] = true;
      LOGGER.info("event=bootstrap_user created=true");
    });
    return created[0];
  }

  boolean resetExistingBootstrap(String suppliedUsername, String newPassword) {
    if (suppliedUsername == null || !USERNAME.matcher(suppliedUsername).matches()) {
      throw new IllegalArgumentException("FKIT_BOOTSTRAP_USERNAME is invalid");
    }
    validatePassword(newPassword);
    String username = suppliedUsername.toLowerCase(Locale.ROOT);
    int[] updated = {0};
    transactions.executeWithoutResult(status -> {
      jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
        try (java.sql.Statement statement = connection.createStatement()) {
          statement.execute("SELECT pg_advisory_xact_lock(1329748801)");
        }
        return null;
      });
      updated[0] = jdbc.update(
          "UPDATE identity.users SET password_hash = ? WHERE username = ? AND is_bootstrap = TRUE",
          passwordEncoder.encode(newPassword), username);
      if (updated[0] != 1) {
        throw new IllegalStateException("Existing bootstrap account was not found; no account was changed");
      }
    });
    LOGGER.info("event=bootstrap_user reset=true");
    return updated[0] == 1;
  }

  private static void validatePassword(String password) {
    if (password.length() < 12 || password.length() > 72
        || password.getBytes(StandardCharsets.UTF_8).length > 72) {
      throw new IllegalArgumentException("FKIT_BOOTSTRAP_PASSWORD must be 12 to 72 characters and at most 72 UTF-8 bytes");
    }
  }

  private String required(String name) {
    String value = environment.apply(name);
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must be set");
    return value;
  }
}
