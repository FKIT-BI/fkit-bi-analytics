package ru.fkit.bi.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.util.Arrays;

@SpringBootApplication
@EnableScheduling
public class AnalyticsApplication {
  public static void main(String[] args) {
    if (args.length > 0 && "bootstrap-user".equals(args[0])) {
      try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(AnalyticsApplication.class)
          .web(WebApplicationType.NONE)
          .properties("fkit.bootstrap.command-enabled=true")
          .run(Arrays.copyOfRange(args, 1, args.length))) {
        // Close the non-web context before the one-shot process exits.
      }
      return;
    }
    SpringApplication.run(AnalyticsApplication.class, args);
  }
}
