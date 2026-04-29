package com.docflow.scenario;

import com.docflow.c3.audit.CallType;
import com.docflow.c3.audit.LlmCallAudit;
import com.docflow.c3.audit.LlmCallAuditId;
import com.docflow.c3.audit.LlmCallAuditWriter;
import com.docflow.c3.events.ExtractionCompleted;
import com.docflow.c3.events.ExtractionFailed;
import com.docflow.c3.llm.LlmExtractor;
import com.docflow.c3.llm.LlmProtocolError;
import com.docflow.c3.llm.LlmSchemaViolation;
import com.docflow.c3.llm.LlmTimeout;
import com.docflow.c3.llm.LlmUnavailable;
import com.docflow.config.AppConfig;
import com.docflow.document.Document;
import com.docflow.document.DocumentReader;
import com.docflow.document.DocumentWriter;
import com.docflow.document.ReextractionStatus;
import com.docflow.ingestion.StoredDocumentId;
import com.docflow.platform.DocumentEventBus;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class ScenarioLlmExtractorStub extends LlmExtractor {

  private final ScenarioContext scenarioContext;
  private final LlmCallAuditWriter auditWriter;
  private final DocumentReader documentReader;
  private final DocumentWriter documentWriter;
  private final DocumentEventBus eventBus;
  private final String modelId;
  private final Clock clock;
  private final AtomicInteger extractFieldsCount = new AtomicInteger();
  private final AtomicInteger extractCount = new AtomicInteger();

  public ScenarioLlmExtractorStub(
      ScenarioContext scenarioContext,
      LlmCallAuditWriter auditWriter,
      DocumentReader documentReader,
      DocumentWriter documentWriter,
      DocumentEventBus eventBus,
      AppConfig appConfig,
      Clock clock) {
    super(null, null, null, null, auditWriter, appConfig, clock);
    this.scenarioContext = scenarioContext;
    this.auditWriter = auditWriter;
    this.documentReader = documentReader;
    this.documentWriter = documentWriter;
    this.eventBus = eventBus;
    this.modelId = appConfig.llm().modelId();
    this.clock = clock;
  }

  @Override
  public Map<String, Object> extractFields(
      UUID storedDocumentId,
      UUID processingDocumentId,
      String organizationId,
      String docTypeId,
      String rawText) {
    extractFieldsCount.incrementAndGet();
    ScenarioFixture.Input input = scenarioContext.matchByRawText(rawText);
    try {
      return runOnce(storedDocumentId, processingDocumentId, null, organizationId, input);
    } catch (LlmSchemaViolation first) {
      return runOnce(storedDocumentId, processingDocumentId, null, organizationId, input);
    }
  }

  @Override
  public void extract(UUID documentId, String newDocTypeId) {
    extractCount.incrementAndGet();
    Document document =
        documentReader
            .get(documentId)
            .orElseThrow(() -> new IllegalArgumentException("document not found: " + documentId));
    ScenarioFixture.Input input = scenarioContext.matchByRawText(document.rawText());
    documentWriter.setReextractionStatus(documentId, ReextractionStatus.IN_PROGRESS);

    Map<String, Object> extracted;
    try {
      extracted =
          retryOnce(
              () ->
                  runOnce(
                      document.storedDocumentId(),
                      null,
                      documentId,
                      document.organizationId(),
                      input));
    } catch (RuntimeException e) {
      documentWriter.setReextractionStatus(documentId, ReextractionStatus.FAILED);
      eventBus.publish(
          new ExtractionFailed(
              documentId, document.organizationId(), e.getMessage(), Instant.now(clock)));
      throw e;
    }

    documentWriter.updateExtraction(documentId, newDocTypeId, extracted);
    documentWriter.setReextractionStatus(documentId, ReextractionStatus.NONE);
    eventBus.publish(
        new ExtractionCompleted(
            documentId, document.organizationId(), extracted, newDocTypeId, Instant.now(clock)));
  }

  public int extractFieldsInvocationCount() {
    return extractFieldsCount.get();
  }

  public int extractInvocationCount() {
    return extractCount.get();
  }

  public void resetInvocationCounts() {
    extractFieldsCount.set(0);
    extractCount.set(0);
  }

  private Map<String, Object> runOnce(
      UUID storedDocumentId,
      UUID processingDocumentId,
      UUID documentId,
      String organizationId,
      ScenarioFixture.Input input) {
    String error = null;
    try {
      ScenarioFixture.Extraction extraction = input.extraction();
      if (extraction != null && extraction.error() != null) {
        RuntimeException toThrow = toException(extraction.error());
        error = toThrow.getMessage();
        throw toThrow;
      }
      if (extraction == null || extraction.fields() == null) {
        throw new IllegalStateException(
            "scenario fixture extraction must declare 'fields' or 'error'");
      }
      return new LinkedHashMap<>(extraction.fields());
    } catch (RuntimeException e) {
      if (error == null) {
        error = e.getMessage();
      }
      throw e;
    } finally {
      auditWriter.insert(
          new LlmCallAudit(
              LlmCallAuditId.generate(),
              StoredDocumentId.of(storedDocumentId),
              processingDocumentId,
              documentId,
              organizationId,
              CallType.EXTRACT,
              modelId,
              error,
              Instant.now(clock)));
    }
  }

  private static Map<String, Object> retryOnce(
      java.util.function.Supplier<Map<String, Object>> op) {
    try {
      return op.get();
    } catch (LlmSchemaViolation first) {
      return op.get();
    }
  }

  private static RuntimeException toException(String name) {
    return switch (name) {
      case "SCHEMA_VIOLATION" -> new LlmSchemaViolation("scenario stub extract schema violation");
      case "UNAVAILABLE" -> new LlmUnavailable("scenario stub extract unavailable");
      case "TIMEOUT" -> new LlmTimeout("scenario stub extract timeout");
      case "PROTOCOL_ERROR" -> new LlmProtocolError("scenario stub extract protocol error");
      default -> new IllegalStateException("unknown scenario error name: " + name);
    };
  }
}
