package ru.fkit.bi.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.util.Arrays;
import java.util.Map;
import org.springframework.core.env.MapPropertySource;

@SpringBootApplication
@EnableScheduling
public class AnalyticsApplication {
  public static void main(String[] args) {
    if (args.length > 0 && ("bootstrap-user".equals(args[0]) || "reset-bootstrap-password".equals(args[0]))) {
      boolean resetOnly = "reset-bootstrap-password".equals(args[0]);
      try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(AnalyticsApplication.class)
          .web(WebApplicationType.NONE)
          .initializers(context -> context.getEnvironment().getPropertySources().addFirst(
              new MapPropertySource("operatorBootstrapCommand",
                  Map.of("fkit.bootstrap.command-enabled", true, "fkit.bootstrap.reset-only", resetOnly))))
          .run(Arrays.copyOfRange(args, 1, args.length))) {
        // Close the non-web context before the one-shot process exits.
      }
      return;
    }
    SpringApplication.run(AnalyticsApplication.class, args);
  }
}
