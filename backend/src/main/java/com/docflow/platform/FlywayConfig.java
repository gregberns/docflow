package com.docflow.platform;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;

// Spring Boot 4.0.0 ships without FlywayAutoConfiguration; this class restores
// the equivalent for DocFlow. V1__init.sql runs once on context refresh, before
// JPA/Hibernate touches the schema. Tests opt out via spring.flyway.enabled=false.
//
// History (df-9kx): an earlier form of this class was a plain @Configuration with
// @ConditionalOnBean(DataSource.class). Under @SpringBootTest the condition
// evaluator ran *before* the DataSource bean was registered (no auto-config
// ordering hint to wait for it), so Flyway silently disappeared from the context
// even when spring.flyway.enabled=true. Promoting to @AutoConfiguration with
// after = DataSourceAutoConfiguration.class fixes the ordering: condition
// evaluation now sees the DataSource and the Flyway bean is created.
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
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
