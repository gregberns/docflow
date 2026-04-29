package com.docflow.scenario;

import com.docflow.c3.audit.CallType;
import com.docflow.c3.audit.LlmCallAudit;
import com.docflow.c3.audit.LlmCallAuditId;
import com.docflow.c3.audit.LlmCallAuditWriter;
import com.docflow.c3.llm.LlmClassifier;
import com.docflow.c3.llm.LlmProtocolError;
import com.docflow.c3.llm.LlmSchemaViolation;
import com.docflow.c3.llm.LlmTimeout;
import com.docflow.c3.llm.LlmUnavailable;
import com.docflow.config.AppConfig;
import com.docflow.config.catalog.OrganizationCatalog;
import com.docflow.ingestion.StoredDocumentId;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class ScenarioLlmClassifierStub extends LlmClassifier {

  private final ScenarioContext scenarioContext;
  private final OrganizationCatalog organizationCatalog;
  private final LlmCallAuditWriter auditWriter;
  private final String modelId;
  private final Clock clock;
  private final AtomicInteger invocationCount = new AtomicInteger();

  public ScenarioLlmClassifierStub(
      ScenarioContext scenarioContext,
      OrganizationCatalog organizationCatalog,
      LlmCallAuditWriter auditWriter,
      AppConfig appConfig,
      Clock clock) {
    super(organizationCatalog, null, null, null, null, auditWriter, appConfig, clock);
    this.scenarioContext = scenarioContext;
    this.organizationCatalog = organizationCatalog;
    this.auditWriter = auditWriter;
    this.modelId = appConfig.llm().modelId();
    this.clock = clock;
  }

  @Override
  public ClassifyResult classify(
      UUID storedDocumentId, UUID processingDocumentId, String organizationId, String rawText) {
    invocationCount.incrementAndGet();
    ScenarioFixture.Input input = scenarioContext.matchByRawText(rawText);
    ScenarioFixture.Classification classification = input.classification();
    String error = null;
    try {
      if (classification.error() != null) {
        RuntimeException toThrow = toException(classification.error(), "classify");
        error = toThrow.getMessage();
        throw toThrow;
      }
      String detected = classification.docType();
      List<String> allowed = organizationCatalog.getAllowedDocTypes(organizationId);
      if (!allowed.contains(detected)) {
        LlmSchemaViolation v =
            new LlmSchemaViolation("classify result '" + detected + "' not in allowed enum");
        error = v.getMessage();
        throw v;
      }
      return new ClassifyResult(detected);
    } finally {
      auditWriter.insert(
          new LlmCallAudit(
              LlmCallAuditId.generate(),
              StoredDocumentId.of(storedDocumentId),
              processingDocumentId,
              null,
              organizationId,
              CallType.CLASSIFY,
              modelId,
              error,
              Instant.now(clock)));
    }
  }

  public int invocationCount() {
    return invocationCount.get();
  }

  public void resetInvocationCount() {
    invocationCount.set(0);
  }

  private static RuntimeException toException(String name, String kind) {
    return switch (name) {
      case "SCHEMA_VIOLATION" ->
          new LlmSchemaViolation("scenario stub " + kind + " schema violation");
      case "UNAVAILABLE" -> new LlmUnavailable("scenario stub " + kind + " unavailable");
      case "TIMEOUT" -> new LlmTimeout("scenario stub " + kind + " timeout");
      case "PROTOCOL_ERROR" -> new LlmProtocolError("scenario stub " + kind + " protocol error");
      default -> new IllegalStateException("unknown scenario error name: " + name);
    };
  }
}
