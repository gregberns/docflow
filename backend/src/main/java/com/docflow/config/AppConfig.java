package com.docflow.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("docflow")
public record AppConfig(
    @Valid @NotNull Llm llm,
    @Valid @NotNull Storage storage,
    @Valid @NotNull Database database,
    @Valid @NotNull OrgConfigBootstrap config) {

  public record Llm(
      @NotBlank String modelId,
      @NotBlank(message = "docflow.llm.apiKey must not be blank") String apiKey,
      @NotNull Duration requestTimeout,
      @Valid @NotNull Eval eval) {
    public record Eval(@NotBlank String reportPath) {}
  }

  public record Storage(@NotBlank String storageRoot) {}

  public record Database(@NotBlank String url, @NotBlank String user, @NotNull String password) {}

  public record OrgConfigBootstrap(boolean seedOnBoot, @NotBlank String seedResourcePath) {}
}
