package com.docflow.platform;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Regression test for df-9kx: under {@link SpringBootTest}, {@link FlywayConfig} must register a
 * {@link Flyway} bean and run {@code migrate()} so the schema is materialized before tests touch
 * the DB.
 *
 * <p>The previous {@code @ConditionalOnBean(DataSource.class)} guard evaluated false because the
 * condition evaluator ran before the DataSource auto-config registered its bean (Spring Boot 4
 * dropped {@code FlywayAutoConfiguration} and with it the ordering hints that used to make this
 * work). The fix drops the guard; this test fails if the regression returns.
 */
@Testcontainers
@SpringBootTest(
    classes = FlywayConfigSpringBootIT.FlywayHarnessApp.class,
    properties = {
      "spring.flyway.enabled=true",
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.main.web-application-type=none"
    })
class FlywayConfigSpringBootIT {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void dataSourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired ApplicationContext context;
  @Autowired DataSource dataSource;

  @Test
  void flywayBeanIsPresentAndMigrationRan() {
    assertThat(context.getBeansOfType(Flyway.class))
        .as("FlywayConfig must register a Flyway bean under @SpringBootTest")
        .hasSize(1);

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    String history =
        jdbc.queryForObject(
            "SELECT to_regclass('public.flyway_schema_history')::text", String.class);
    assertThat(history)
        .as("flyway_schema_history must exist after Flyway.migrate() ran on context refresh")
        .isEqualTo("flyway_schema_history");

    Integer applied =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = TRUE",
            Integer.class);
    assertThat(applied).as("V1__init must be recorded as applied").isEqualTo(1);

    String organizations =
        jdbc.queryForObject("SELECT to_regclass('public.organizations')::text", String.class);
    assertThat(organizations)
        .as("V1__init must materialize the organizations table")
        .isEqualTo("organizations");
  }

  @SpringBootApplication(scanBasePackages = "com.docflow.platform")
  static class FlywayHarnessApp {}
}
