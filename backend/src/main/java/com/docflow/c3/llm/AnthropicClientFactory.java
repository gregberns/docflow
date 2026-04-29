package com.docflow.c3.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.docflow.config.AppConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "docflow.llm.api-key")
public class AnthropicClientFactory {

  @Bean(destroyMethod = "close")
  public AnthropicClient anthropicClient(AppConfig appConfig) {
    AppConfig.Llm llm = appConfig.llm();
    return AnthropicOkHttpClient.builder()
        .apiKey(llm.apiKey())
        .timeout(llm.requestTimeout())
        .maxRetries(0)
        .build();
  }
}
