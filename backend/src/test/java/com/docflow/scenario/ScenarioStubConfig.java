package com.docflow.scenario;

import com.docflow.c3.audit.LlmCallAuditWriter;
import com.docflow.c3.llm.LlmClassifier;
import com.docflow.c3.llm.LlmExtractor;
import com.docflow.config.AppConfig;
import com.docflow.config.catalog.OrganizationCatalog;
import com.docflow.document.DocumentReader;
import com.docflow.document.DocumentWriter;
import com.docflow.platform.DocumentEventBus;
import java.time.Clock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

@TestConfiguration
@Profile("scenario")
public class ScenarioStubConfig {

  @Bean
  public ScenarioContext scenarioContext() {
    return new ScenarioContext();
  }

  @Bean
  public ScenarioRecorder scenarioRecorder() {
    return new ScenarioRecorder();
  }

  @Bean
  @Primary
  public LlmClassifier scenarioLlmClassifier(
      ScenarioContext scenarioContext,
      OrganizationCatalog organizationCatalog,
      LlmCallAuditWriter auditWriter,
      AppConfig appConfig,
      Clock clock) {
    return new ScenarioLlmClassifierStub(
        scenarioContext, organizationCatalog, auditWriter, appConfig, clock);
  }

  @Bean
  @Primary
  public LlmExtractor scenarioLlmExtractor(
      ScenarioContext scenarioContext,
      LlmCallAuditWriter auditWriter,
      DocumentReader documentReader,
      DocumentWriter documentWriter,
      DocumentEventBus eventBus,
      AppConfig appConfig,
      Clock clock) {
    return new ScenarioLlmExtractorStub(
        scenarioContext, auditWriter, documentReader, documentWriter, eventBus, appConfig, clock);
  }
}
