package com.docflow.c3.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.anthropic.client.AnthropicClient;
import com.docflow.config.AppConfig;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AnthropicClientFactoryTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
          .withUserConfiguration(EnableConfig.class, AnthropicClientFactory.class)
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
  void factoryProducesAnthropicClientWhenApiKeyIsSet() {
    contextRunner
        .withPropertyValues("docflow.llm.api-key=sk-ant-test-key")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(AnthropicClient.class);
              AnthropicClient client = context.getBean(AnthropicClient.class);
              assertThat(client).isNotNull();
            });
  }

  @Test
  void factoryIsSkippedWhenApiKeyPropertyIsAbsent() {
    new ApplicationContextRunner()
        .withUserConfiguration(AnthropicClientFactory.class)
        .withBean(
            AppConfig.class,
            () ->
                new AppConfig(
                    new AppConfig.Llm(
                        "claude-sonnet-4-6",
                        "sk-ant-test-key",
                        Duration.ofSeconds(60),
                        new AppConfig.Llm.Eval("eval/reports/latest.md")),
                    new AppConfig.Storage("/tmp/docflow"),
                    new AppConfig.Database(
                        "jdbc:postgresql://localhost:5432/docflow", "docflow", "docflow"),
                    new AppConfig.OrgConfigBootstrap(true, "classpath:seed/")))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(AnthropicClient.class);
            });
  }

  @org.springframework.context.annotation.Configuration
  @org.springframework.boot.context.properties.EnableConfigurationProperties(AppConfig.class)
  static class EnableConfig {}
}
