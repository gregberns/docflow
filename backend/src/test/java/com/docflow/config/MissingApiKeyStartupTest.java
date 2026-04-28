package com.docflow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MissingApiKeyStartupTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
          .withUserConfiguration(EnableConfig.class)
          .withPropertyValues(
              "docflow.llm.model-id=claude-sonnet-4-6",
              "docflow.llm.request-timeout=PT60S",
              "docflow.llm.eval.report-path=eval/reports/latest.md",
              "docflow.storage.storage-root=/tmp/docflow",
              "docflow.database.url=jdbc:postgresql://localhost:5432/docflow",
              "docflow.database.user=docflow",
              "docflow.database.password=docflow",
              "docflow.config.seed-on-boot=true",
              "docflow.config.seed-resource-path=classpath:seed/");

  @Test
  void blankApiKeyFailsStartupWithBindValidationException() {
    contextRunner
        .withPropertyValues("docflow.llm.api-key=")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThatThrownBy(() -> context.getBean(AppConfig.class))
                  .as("ApplicationContext refresh must fail before AppConfig is available")
                  .isNotNull();

              Throwable failure = context.getStartupFailure();
              assertThat(failure)
                  .as("startup failure should be a ConfigurationPropertiesBindException")
                  .isInstanceOf(ConfigurationPropertiesBindException.class);

              Throwable cause = rootCauseOfType(failure, BindValidationException.class);
              assertThat(cause)
                  .as("root cause must be BindValidationException")
                  .isInstanceOf(BindValidationException.class);
              assertThat(cause).hasMessageContaining("docflow.llm.apiKey");
            });
  }

  @Test
  void validApiKeyAllowsContextToStart() {
    contextRunner
        .withPropertyValues("docflow.llm.api-key=sk-ant-test-key")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              AppConfig config = context.getBean(AppConfig.class);
              assertThat(config.llm().apiKey()).isEqualTo("sk-ant-test-key");
              assertThat(config.llm().modelId()).isEqualTo("claude-sonnet-4-6");
            });
  }

  private static Throwable rootCauseOfType(Throwable t, Class<? extends Throwable> type) {
    Throwable current = t;
    while (current != null) {
      if (type.isInstance(current)) {
        return current;
      }
      current = current.getCause();
    }
    return null;
  }

  @org.springframework.context.annotation.Configuration
  @org.springframework.boot.context.properties.EnableConfigurationProperties(AppConfig.class)
  static class EnableConfig {}
}
