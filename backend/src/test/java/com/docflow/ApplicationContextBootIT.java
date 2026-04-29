package com.docflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.docflow.config.AppConfig;
import com.docflow.config.AppConfigBeans;
import com.docflow.ingestion.storage.FilesystemStoredDocumentStorage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Asserts the production wiring path for {@link FilesystemStoredDocumentStorage} — its only ctor
 * arg is {@link AppConfig.Storage}, a nested record on {@link AppConfig}. {@link
 * org.springframework.boot.context.properties.ConfigurationPropertiesScan} only registers {@code
 * AppConfig} itself; its nested records need an explicit {@link
 * org.springframework.context.annotation.Bean} exposure ({@link AppConfigBeans}). df-rar shipped
 * without that exposure and the backend crashed at boot. This test would have failed at that
 * commit.
 *
 * <p>The harness is deliberately narrow — only the configuration-properties machinery, {@link
 * AppConfigBeans}, and the consumer class. No Postgres, no Flyway, no ApplicationReadyEvent
 * listeners. The bug surfaces at context refresh, so context refresh is what we exercise.
 */
class ApplicationContextBootIT {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
          .withUserConfiguration(EnableAppConfig.class, AppConfigBeans.class)
          .withBean(FilesystemStoredDocumentStorage.class)
          .withPropertyValues(
              "docflow.llm.model-id=claude-sonnet-4-6",
              "docflow.llm.api-key=sk-ant-test",
              "docflow.llm.request-timeout=PT60S",
              "docflow.llm.eval.report-path=eval/reports/latest.md",
              "docflow.storage.storage-root=/tmp/docflow",
              "docflow.database.url=jdbc:postgresql://localhost:5432/docflow",
              "docflow.database.user=docflow",
              "docflow.database.password=docflow",
              "docflow.config.seed-on-boot=false",
              "docflow.config.seed-resource-path=classpath:seed/");

  @Test
  void filesystemStoredDocumentStorageWiresAgainstNestedAppConfigStorageBean() {
    contextRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(FilesystemStoredDocumentStorage.class);

          AppConfig appConfig = context.getBean(AppConfig.class);
          assertThat(context.getBean(AppConfig.Storage.class)).isSameAs(appConfig.storage());
          assertThat(context.getBean(AppConfig.Llm.class)).isSameAs(appConfig.llm());
          assertThat(context.getBean(AppConfig.Database.class)).isSameAs(appConfig.database());
          assertThat(context.getBean(AppConfig.OrgConfigBootstrap.class))
              .isSameAs(appConfig.config());
        });
  }

  @Test
  void droppingNestedBeansBreaksFilesystemStorageWiring() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
        .withUserConfiguration(EnableAppConfig.class)
        .withBean(FilesystemStoredDocumentStorage.class)
        .withPropertyValues(
            "docflow.llm.model-id=claude-sonnet-4-6",
            "docflow.llm.api-key=sk-ant-test",
            "docflow.llm.request-timeout=PT60S",
            "docflow.llm.eval.report-path=eval/reports/latest.md",
            "docflow.storage.storage-root=/tmp/docflow",
            "docflow.database.url=jdbc:postgresql://localhost:5432/docflow",
            "docflow.database.user=docflow",
            "docflow.database.password=docflow",
            "docflow.config.seed-on-boot=false",
            "docflow.config.seed-resource-path=classpath:seed/")
        .run(
            context -> {
              assertThat(context)
                  .as(
                      "without AppConfigBeans the FilesystemStoredDocumentStorage ctor cannot resolve"
                          + " AppConfig.Storage — this asserts the regression-test guard itself"
                          + " actually catches the missing wiring rather than passing for an"
                          + " unrelated reason.")
                  .hasFailed();
              assertThat(context.getStartupFailure())
                  .hasMessageContaining("com.docflow.config.AppConfig$Storage");
            });
  }

  @Configuration
  @EnableConfigurationProperties(AppConfig.class)
  static class EnableAppConfig {}
}
