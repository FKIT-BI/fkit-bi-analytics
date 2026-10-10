package ru.fkit.bi.analytics.health;

import liquibase.exception.LiquibaseException;
import liquibase.integration.spring.SpringLiquibase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
class LiquibaseMigrationConfiguration {
  @Bean
  @ConfigurationProperties(prefix = "spring.liquibase")
  @ConditionalOnProperty(prefix = "spring.liquibase", name = "enabled", havingValue = "true", matchIfMissing = true)
  SafeSpringLiquibase liquibase(DataSource dataSource, LiquibaseMigrationState migrationState) {
    SafeSpringLiquibase liquibase = new SafeSpringLiquibase(migrationState);
    liquibase.setDataSource(dataSource);
    return liquibase;
  }

  static final class SafeSpringLiquibase extends SpringLiquibase {
    private static final Logger logger = LoggerFactory.getLogger(SafeSpringLiquibase.class);
    private final LiquibaseMigrationState migrationState;

    SafeSpringLiquibase(LiquibaseMigrationState migrationState) {
      this.migrationState = migrationState;
    }

    @Override
    public void afterPropertiesSet() throws LiquibaseException {
      try {
        super.afterPropertiesSet();
        migrationState.succeeded();
      } catch (LiquibaseException | RuntimeException exception) {
        migrationState.failed();
        logger.error("Liquibase startup migration failed; readiness will remain DOWN", exception);
      }
    }
  }
}
