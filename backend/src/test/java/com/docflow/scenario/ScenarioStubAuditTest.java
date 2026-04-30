package com.docflow.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docflow.c3.audit.CallType;
import com.docflow.c3.audit.LlmCallAudit;
import com.docflow.c3.audit.LlmCallAuditWriter;
import com.docflow.c3.llm.LlmSchemaViolation;
import com.docflow.config.AppConfig;
import com.docflow.config.catalog.OrganizationCatalog;
import com.docflow.document.DocumentReader;
import com.docflow.document.DocumentWriter;
import com.docflow.platform.DocumentEventBus;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScenarioStubAuditTest {

  private static final String ORG = "pinnacle-legal";
  private static final String RAW_TEXT = "ACME INVOICE\nTotal: 100.00";

  private final AppConfig appConfig =
      new AppConfig(
          new AppConfig.Llm(
              "test-model",
              "sk-test",
              java.time.Duration.ofSeconds(60),
              new AppConfig.Llm.Eval("x")),
          new AppConfig.Storage("/tmp"),
          new AppConfig.Database("u", "p", "x"),
          new AppConfig.OrgConfigBootstrap(false, "classpath:seed/"));
  private final Clock clock =
      Clock.fixed(java.time.Instant.parse("2026-04-29T10:00:00Z"), ZoneId.of("UTC"));

  private RecordingAuditWriter auditWriter;
  private ScenarioContext scenarioContext;

  @BeforeEach
  void setUp() {
    auditWriter = new RecordingAuditWriter();
    scenarioContext = new InMemoryScenarioContext();
  }

  @Test
  void classifierStub_writesOneAuditRow_onSuccess() {
    OrganizationCatalog catalog = new StubOrgCatalog(List.of("invoice", "receipt"));
    ScenarioLlmClassifierStub stub =
        new ScenarioLlmClassifierStub(scenarioContext, catalog, auditWriter, appConfig, clock);

    stub.classify(UUID.randomUUID(), UUID.randomUUID(), ORG, RAW_TEXT);

    assertThat(auditWriter.rows).hasSize(1);
    assertThat(auditWriter.rows.get(0).callType()).isEqualTo(CallType.CLASSIFY);
    assertThat(auditWriter.rows.get(0).error()).isNull();
  }

  @Test
  void classifierStub_writesAuditRow_onSchemaViolation_whenDocTypeNotAllowed() {
    OrganizationCatalog catalog = new StubOrgCatalog(List.of("receipt"));
    ScenarioLlmClassifierStub stub =
        new ScenarioLlmClassifierStub(scenarioContext, catalog, auditWriter, appConfig, clock);

    assertThatThrownBy(() -> stub.classify(UUID.randomUUID(), UUID.randomUUID(), ORG, RAW_TEXT))
        .isInstanceOf(LlmSchemaViolation.class);

    assertThat(auditWriter.rows).hasSize(1);
    assertThat(auditWriter.rows.get(0).callType()).isEqualTo(CallType.CLASSIFY);
    assertThat(auditWriter.rows.get(0).error()).contains("not in allowed enum");
  }

  @Test
  void extractorStub_writesOneAuditRow_onSuccess() {
    DocumentReader reader = id -> java.util.Optional.empty();
    DocumentWriter writer = new NoopDocumentWriter();
    DocumentEventBus eventBus = new DocumentEventBus(new NoopEventPublisher());

    ScenarioLlmExtractorStub stub =
        new ScenarioLlmExtractorStub(
            scenarioContext, auditWriter, reader, writer, eventBus, appConfig, clock);

    Map<String, Object> result =
        stub.extractFields(UUID.randomUUID(), UUID.randomUUID(), ORG, "invoice", RAW_TEXT);

    assertThat(result).containsEntry("vendor", "Acme");
    assertThat(auditWriter.rows).hasSize(1);
    assertThat(auditWriter.rows.get(0).callType()).isEqualTo(CallType.EXTRACT);
    assertThat(auditWriter.rows.get(0).error()).isNull();
  }

  @Test
  void extractorStub_terminalSchemaViolation_throwsAfterRetryAndAuditsBothAttempts() {
    DocumentReader reader = id -> java.util.Optional.empty();
    DocumentWriter writer = new NoopDocumentWriter();
    DocumentEventBus eventBus = new DocumentEventBus(new NoopEventPublisher());

    ScenarioContext failingContext =
        new ScenarioContext() {
          @Override
          public ScenarioFixture.Input matchByRawText(String rawText) {
            return new ScenarioFixture.Input(
                "x.pdf",
                ORG,
                new ScenarioFixture.Classification("invoice", null),
                new ScenarioFixture.Extraction(null, "SCHEMA_VIOLATION"));
          }
        };

    ScenarioLlmExtractorStub stub =
        new ScenarioLlmExtractorStub(
            failingContext, auditWriter, reader, writer, eventBus, appConfig, clock);

    assertThatThrownBy(
            () ->
                stub.extractFields(UUID.randomUUID(), UUID.randomUUID(), ORG, "invoice", RAW_TEXT))
        .isInstanceOf(LlmSchemaViolation.class);

    assertThat(auditWriter.rows).as("two failed attempts, both audited").hasSize(2);
    assertThat(auditWriter.rows.get(0).error()).contains("schema violation");
    assertThat(auditWriter.rows.get(1).error()).contains("schema violation");
  }

  static final class InMemoryScenarioContext extends ScenarioContext {
    @Override
    public ScenarioFixture.Input matchByRawText(String rawText) {
      return new ScenarioFixture.Input(
          "x.pdf",
          ORG,
          new ScenarioFixture.Classification("invoice", null),
          new ScenarioFixture.Extraction(Map.of("vendor", "Acme"), null));
    }
  }

  static final class StubOrgCatalog implements OrganizationCatalog {
    private final List<String> allowed;

    StubOrgCatalog(List<String> allowed) {
      this.allowed = allowed;
    }

    @Override
    public java.util.Optional<com.docflow.config.catalog.OrganizationView> getOrganization(
        String orgId) {
      return java.util.Optional.empty();
    }

    @Override
    public List<com.docflow.config.catalog.OrganizationView> listOrganizations() {
      return List.of();
    }

    @Override
    public List<String> getAllowedDocTypes(String orgId) {
      return allowed;
    }
  }

  static final class RecordingAuditWriter implements LlmCallAuditWriter {
    final List<LlmCallAudit> rows = new CopyOnWriteArrayList<>();

    @Override
    public void insert(LlmCallAudit audit) {
      rows.add(audit);
    }
  }

  static final class NoopDocumentWriter implements DocumentWriter {
    @Override
    public void insert(com.docflow.document.Document document) {}

    @Override
    public void updateExtraction(
        UUID documentId, String detectedDocumentType, Map<String, Object> fields) {}

    @Override
    public void setReextractionStatus(
        UUID documentId, com.docflow.document.ReextractionStatus status) {}
  }

  static final class NoopEventPublisher
      implements org.springframework.context.ApplicationEventPublisher {
    @Override
    public void publishEvent(Object event) {}
  }
}
