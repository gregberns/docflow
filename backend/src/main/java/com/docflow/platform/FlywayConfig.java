package com.docflow.platform;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Spring Boot 4.0.0 ships without FlywayAutoConfiguration; this bean wires
// Flyway manually against the application DataSource so V1__init.sql runs
// once at startup. Fragment-level ITs opt out via spring.flyway.enabled=false;
// context-only tests (no DataSource bean) skip via @ConditionalOnBean.
@Configuration
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true", matchIfMissing = true)
public class FlywayConfig {

  @Bean(initMethod = "migrate")
  public Flyway flyway(DataSource dataSource) {
    return Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .baselineOnMigrate(false)
        .load();
  }
}
