package ru.fkit.bi.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"spring.flyway.enabled=false", "spring.datasource.url=jdbc:h2:mem:testdb"})
class AnalyticsApplicationTest { @Test void contextLoads() {} }
