package com.docflow.c3.llm;

import com.anthropic.core.JsonValue;
import com.docflow.c3.audit.CallType;
import com.docflow.c3.audit.LlmCallAudit;
import com.docflow.c3.audit.LlmCallAuditId;
import com.docflow.c3.audit.LlmCallAuditWriter;
import com.docflow.c3.events.ExtractionCompleted;
import com.docflow.c3.events.ExtractionFailed;
import com.docflow.config.AppConfig;
import com.docflow.config.catalog.DocumentTypeCatalog;
import com.docflow.config.catalog.DocumentTypeSchemaView;
import com.docflow.document.Document;
import com.docflow.document.ReextractionStatus;
import com.docflow.ingestion.StoredDocumentId;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class LlmExtractor {

  private final DocumentTypeCatalog documentTypeCatalog;
  private final ExtractRequestBuilder requestBuilder;
  private final RetypeDocumentSink sink;
  private final LlmCallExecutor executor;
  private final LlmCallAuditWriter auditWriter;
  private final String modelId;
  private final Clock clock;

  public LlmExtractor(
      DocumentTypeCatalog documentTypeCatalog,
      ExtractRequestBuilder requestBuilder,
      RetypeDocumentSink sink,
      LlmCallExecutor executor,
      LlmCallAuditWriter auditWriter,
      AppConfig appConfig,
      Clock clock) {
    this.documentTypeCatalog = documentTypeCatalog;
    this.requestBuilder = requestBuilder;
    this.sink = sink;
    this.executor = executor;
    this.auditWriter = auditWriter;
    this.modelId = appConfig.llm().modelId();
    this.clock = clock;
  }

  public Map<String, Object> extractFields(
      UUID storedDocumentId,
      UUID processingDocumentId,
      String organizationId,
      String docTypeId,
      String rawText) {
    Objects.requireNonNull(storedDocumentId, "storedDocumentId");
    Objects.requireNonNull(processingDocumentId, "processingDocumentId");
    if (organizationId == null || organizationId.isBlank()) {
      throw new IllegalArgumentException("organizationId must not be blank");
    }
    if (docTypeId == null || docTypeId.isBlank()) {
      throw new IllegalArgumentException("docTypeId must not be blank");
    }
    if (rawText == null || rawText.isBlank()) {
      throw new IllegalArgumentException("rawText must not be blank");
    }

    DocumentTypeSchemaView schema = lookupDocType(organizationId, docTypeId);
    return runWithRetry(
        storedDocumentId, processingDocumentId, null, organizationId, schema, rawText);
  }

  public void extract(UUID documentId, String newDocTypeId) {
    Objects.requireNonNull(documentId, "documentId");
    if (newDocTypeId == null || newDocTypeId.isBlank()) {
      throw new IllegalArgumentException("newDocTypeId must not be blank");
    }

    Document document =
        sink.reader()
            .get(documentId)
            .orElseThrow(() -> new IllegalArgumentException("document not found: " + documentId));
    if (document.reextractionStatus() == ReextractionStatus.IN_PROGRESS) {
      throw new RetypeAlreadyInProgressException(documentId);
    }

    DocumentTypeSchemaView schema = lookupDocType(document.organizationId(), newDocTypeId);
    sink.writer().setReextractionStatus(documentId, ReextractionStatus.IN_PROGRESS);

    Map<String, Object> extracted;
    try {
      extracted =
          runWithRetry(
              document.storedDocumentId(),
              null,
              documentId,
              document.organizationId(),
              schema,
              document.rawText());
    } catch (RuntimeException e) {
      sink.writer().setReextractionStatus(documentId, ReextractionStatus.FAILED);
      sink.eventBus()
          .publish(
              new ExtractionFailed(
                  documentId, document.organizationId(), e.getMessage(), Instant.now(clock)));
      throw e;
    }

    sink.writer().updateExtraction(documentId, newDocTypeId, extracted);
    sink.writer().setReextractionStatus(documentId, ReextractionStatus.NONE);
    sink.eventBus()
        .publish(
            new ExtractionCompleted(
                documentId,
                document.organizationId(),
                extracted,
                newDocTypeId,
                Instant.now(clock)));
  }

  private DocumentTypeSchemaView lookupDocType(String organizationId, String docTypeId) {
    return documentTypeCatalog
        .getDocumentTypeSchema(organizationId, docTypeId)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "document type '"
                        + docTypeId
                        + "' not registered for organization '"
                        + organizationId
                        + "'"));
  }

  private Map<String, Object> runWithRetry(
      UUID storedDocumentId,
      UUID processingDocumentId,
      UUID documentId,
      String organizationId,
      DocumentTypeSchemaView schema,
      String rawText) {
    try {
      return runOnce(
          storedDocumentId, processingDocumentId, documentId, organizationId, schema, rawText);
    } catch (LlmSchemaViolation first) {
      return runOnce(
          storedDocumentId, processingDocumentId, documentId, organizationId, schema, rawText);
    }
  }

  private Map<String, Object> runOnce(
      UUID storedDocumentId,
      UUID processingDocumentId,
      UUID documentId,
      String organizationId,
      DocumentTypeSchemaView schema,
      String rawText) {
    ExtractRequestBuilder.Built built = requestBuilder.build(modelId, schema, rawText);
    String error = null;
    try {
      JsonValue input = executor.execute(built.params(), built.toolSchema());
      return readFields(input);
    } catch (RuntimeException e) {
      error = e.getMessage();
      throw e;
    } finally {
      writeAudit(storedDocumentId, processingDocumentId, documentId, organizationId, error);
    }
  }

  private static Map<String, Object> readFields(JsonValue input) {
    @SuppressWarnings(
        "unchecked") // why: SDK-side Jackson 2 deserialization to a Map<String,Object>.
    Map<String, Object> map = input.convert(Map.class);
    if (map == null) {
      throw new LlmSchemaViolation("extract tool input was empty");
    }
    return new LinkedHashMap<>(map);
  }

  private void writeAudit(
      UUID storedDocumentId,
      UUID processingDocumentId,
      UUID documentId,
      String organizationId,
      String error) {
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
