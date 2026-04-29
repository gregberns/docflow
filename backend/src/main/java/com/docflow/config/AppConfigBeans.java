package com.docflow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Spring's @ConfigurationPropertiesScan registers AppConfig itself as a bean,
// but its nested records (Storage, Llm, Database, OrgConfigBootstrap) are not
// auto-registered. Consumers that inject a nested record directly need an
// explicit @Bean exposure; keep one method per nested record so the public
// surface of AppConfig and the bean graph stay one-to-one.
@Configuration
public class AppConfigBeans {

  @Bean
  public AppConfig.Storage storage(AppConfig appConfig) {
    return appConfig.storage();
  }

  @Bean
  public AppConfig.Llm llm(AppConfig appConfig) {
    return appConfig.llm();
  }

  @Bean
  public AppConfig.Database database(AppConfig appConfig) {
    return appConfig.database();
  }

  @Bean
  public AppConfig.OrgConfigBootstrap orgConfigBootstrap(AppConfig appConfig) {
    return appConfig.config();
  }
}
