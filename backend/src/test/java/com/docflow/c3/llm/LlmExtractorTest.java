package com.docflow.c3.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.docflow.c3.audit.CallType;
import com.docflow.c3.audit.LlmCallAudit;
import com.docflow.c3.audit.LlmCallAuditWriter;
import com.docflow.c3.events.ExtractionCompleted;
import com.docflow.c3.events.ExtractionFailed;
import com.docflow.config.AppConfig;
import com.docflow.config.catalog.DocumentTypeCatalog;
import com.docflow.config.catalog.DocumentTypeSchemaView;
import com.docflow.config.catalog.FieldView;
import com.docflow.document.Document;
import com.docflow.document.DocumentReader;
import com.docflow.document.DocumentWriter;
import com.docflow.document.ReextractionStatus;
import com.docflow.platform.DocumentEvent;
import com.docflow.platform.DocumentEventBus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LlmExtractorTest {

  private static final String ORG_ID = "riverside-bistro";
  private static final String DOC_TYPE_ID = "invoice";
  private static final String NEW_DOC_TYPE_ID = "receipt";
  private static final String MODEL_ID = "claude-sonnet-4-6";
  private static final String RAW_TEXT = "raw document text body";
  private static final ToolSchema EXTRACT_TOOL_SCHEMA =
      new ToolSchema("extract_invoice", "{\"type\":\"object\"}");
  private static final ToolSchema RECEIPT_TOOL_SCHEMA =
      new ToolSchema("extract_receipt", "{\"type\":\"object\"}");

  private DocumentTypeCatalog documentTypeCatalog;
  private DocumentReader documentReader;
  private DocumentWriter documentWriter;
  private ExtractRequestBuilder requestBuilder;
  private LlmCallExecutor executor;
  private LlmCallAuditWriter auditWriter;
  private DocumentEventBus eventBus;
  private LlmExtractor extractor;

  @BeforeEach
  void setUp() {
    documentTypeCatalog = mock(DocumentTypeCatalog.class);
    documentReader = mock(DocumentReader.class);
    documentWriter = mock(DocumentWriter.class);
    requestBuilder = mock(ExtractRequestBuilder.class);
    executor = mock(LlmCallExecutor.class);
    auditWriter = mock(LlmCallAuditWriter.class);
    eventBus = mock(DocumentEventBus.class);

    DocumentTypeSchemaView invoiceSchema =
        new DocumentTypeSchemaView(
            ORG_ID,
            DOC_TYPE_ID,
            "Invoice",
            List.of(new FieldView("vendor", "STRING", true, null, null, null, null, false)));
    DocumentTypeSchemaView receiptSchema =
        new DocumentTypeSchemaView(
            ORG_ID,
            NEW_DOC_TYPE_ID,
            "Receipt",
            List.of(new FieldView("merchant", "STRING", true, null, null, null, null, false)));

    when(documentTypeCatalog.getDocumentTypeSchema(ORG_ID, DOC_TYPE_ID))
        .thenReturn(Optional.of(invoiceSchema));
    when(documentTypeCatalog.getDocumentTypeSchema(ORG_ID, NEW_DOC_TYPE_ID))
        .thenReturn(Optional.of(receiptSchema));

    MessageCreateParams params =
        MessageCreateParams.builder().model(MODEL_ID).maxTokens(2048).addUserMessage("x").build();
    when(requestBuilder.build(eq(MODEL_ID), eq(invoiceSchema), any()))
        .thenReturn(new ExtractRequestBuilder.Built(params, EXTRACT_TOOL_SCHEMA));
    when(requestBuilder.build(eq(MODEL_ID), eq(receiptSchema), any()))
        .thenReturn(new ExtractRequestBuilder.Built(params, RECEIPT_TOOL_SCHEMA));

    AppConfig appConfig =
        new AppConfig(
            new AppConfig.Llm(
                MODEL_ID,
                "test-key",
                Duration.ofSeconds(60),
                new AppConfig.Llm.Eval("eval/reports/latest.md")),
            new AppConfig.Storage("/tmp/storage"),
            new AppConfig.Database("jdbc:postgresql://localhost/x", "u", ""),
            new AppConfig.OrgConfigBootstrap(false, "config/seed.yaml"));

    Clock clock = Clock.fixed(Instant.parse("2026-04-28T12:00:00Z"), ZoneOffset.UTC);

    RetypeDocumentSink sink = new RetypeDocumentSink(documentReader, documentWriter, eventBus);
    extractor =
        new LlmExtractor(
            documentTypeCatalog, requestBuilder, sink, executor, auditWriter, appConfig, clock);
  }

  @Test
  void initialPipelineHappyPathReturnsFieldsAndDoesNotTouchDocument() {
    UUID storedId = UUID.randomUUID();
    UUID procId = UUID.randomUUID();
    Map<String, Object> expected = Map.of("vendor", "Acme Co");
    when(executor.execute(any(MessageCreateParams.class), any(ToolSchema.class)))
        .thenReturn(JsonValue.from(expected));

    Map<String, Object> result =
        extractor.extractFields(storedId, procId, ORG_ID, DOC_TYPE_ID, RAW_TEXT);

    assertThat(result).containsAllEntriesOf(expected);
    verify(executor, times(1)).execute(any(), any());
    verify(auditWriter, times(1)).insert(any());
    verify(documentWriter, never()).updateExtraction(any(), any(), any());
    verify(documentWriter, never()).insert(any());
    verify(documentWriter, never()).setReextractionStatus(any(), any());
    verify(eventBus, never()).publish(any());
  }

  @Test
  void initialPipelineRetryThenSucceedYieldsTwoAuditRowsAndSecondMap() {
    UUID storedId = UUID.randomUUID();
    UUID procId = UUID.randomUUID();
    Map<String, Object> first = Map.of("vendor", "Wrong");
    Map<String, Object> second = Map.of("vendor", "Right");
    when(executor.execute(any(MessageCreateParams.class), any(ToolSchema.class)))
        .thenThrow(new LlmSchemaViolation("missing required vendor"))
        .thenReturn(JsonValue.from(second));

    Map<String, Object> result =
        extractor.extractFields(storedId, procId, ORG_ID, DOC_TYPE_ID, RAW_TEXT);

    assertThat(result).isEqualTo(second);
    assertThat(result).isNotEqualTo(first);
    ArgumentCaptor<LlmCallAudit> captor = ArgumentCaptor.forClass(LlmCallAudit.class);
    verify(auditWriter, times(2)).insert(captor.capture());
    List<LlmCallAudit> audits = captor.getAllValues();
    assertThat(audits.get(0).error()).isEqualTo("LlmCall failed: LlmSchemaViolation");
    assertThat(audits.get(0).error()).doesNotContain("missing required vendor");
    assertThat(audits.get(1).error()).isNull();
    verify(executor, times(2)).execute(any(), any());
  }

  @Test
  void initialPipelineSecondSchemaViolationPropagates() {
    UUID storedId = UUID.randomUUID();
    UUID procId = UUID.randomUUID();
    when(executor.execute(any(MessageCreateParams.class), any(ToolSchema.class)))
        .thenThrow(new LlmSchemaViolation("first"))
        .thenThrow(new LlmSchemaViolation("second"));

    assertThatThrownBy(
            () -> extractor.extractFields(storedId, procId, ORG_ID, DOC_TYPE_ID, RAW_TEXT))
        .isInstanceOf(LlmSchemaViolation.class)
        .hasMessage("LlmCall failed: LlmSchemaViolation");

    verify(executor, times(2)).execute(any(), any());
    verify(auditWriter, times(2)).insert(any());
  }

  @Test
  void initialPipelineTransportErrorPropagatesWithoutRetry() {
    UUID storedId = UUID.randomUUID();
    UUID procId = UUID.randomUUID();
    when(executor.execute(any(MessageCreateParams.class), any(ToolSchema.class)))
        .thenThrow(new LlmTimeout("anthropic call timed out"));

    assertThatThrownBy(
            () -> extractor.extractFields(storedId, procId, ORG_ID, DOC_TYPE_ID, RAW_TEXT))
        .isInstanceOf(LlmTimeout.class);

    verify(executor, times(1)).execute(any(), any());
    verify(auditWriter, times(1)).insert(any());
  }

  @Test
  void initialPipelineProtocolErrorPropagatesWithoutRetry() {
    UUID storedId = UUID.randomUUID();
    UUID procId = UUID.randomUUID();
    when(executor.execute(any(MessageCreateParams.class), any(ToolSchema.class)))
        .thenThrow(new LlmProtocolError("missing tool_use block"));

    assertThatThrownBy(
            () -> extractor.extractFields(storedId, procId, ORG_ID, DOC_TYPE_ID, RAW_TEXT))
        .isInstanceOf(LlmProtocolError.class);

    verify(executor, times(1)).execute(any(), any());
    verify(auditWriter, times(1)).insert(any());
  }

  @Test
  void initialPipelineUnavailablePropagatesWithoutRetry() {
    UUID storedId = UUID.randomUUID();
    UUID procId = UUID.randomUUID();
    when(executor.execute(any(MessageCreateParams.class), any(ToolSchema.class)))
        .thenThrow(new LlmUnavailable("anthropic service returned status 503"));

    assertThatThrownBy(
            () -> extractor.extractFields(storedId, procId, ORG_ID, DOC_TYPE_ID, RAW_TEXT))
        .isInstanceOf(LlmUnavailable.class);

    verify(executor, times(1)).execute(any(), any());
    verify(auditWriter, times(1)).insert(any());
  }

  @Test
  void retypeHappyPathUpdatesDocumentAndPublishesCompletedEvent() {
    UUID documentId = UUID.randomUUID();
    UUID storedId = UUID.randomUUID();
    Document document =
        new Document(
            documentId,
            storedId,
            ORG_ID,
            DOC_TYPE_ID,
            Map.of("vendor", "Old Vendor"),
            RAW_TEXT,
            Instant.parse("2026-04-27T10:00:00Z"),
            ReextractionStatus.NONE);
    when(documentReader.get(documentId)).thenReturn(Optional.of(document));
    when(documentWriter.claimReextractionInProgress(documentId)).thenReturn(true);
    Map<String, Object> newFields = Map.of("merchant", "New Merchant");
    when(executor.execute(any(MessageCreateParams.class), any(ToolSchema.class)))
        .thenReturn(JsonValue.from(newFields));

    extractor.extract(documentId, NEW_DOC_TYPE_ID);

    verify(documentWriter).claimReextractionInProgress(documentId);
    verify(documentWriter, never())
        .setReextractionStatus(documentId, ReextractionStatus.IN_PROGRESS);
    ArgumentCaptor<Map<String, Object>> fieldsCaptor = mapCaptor();
    verify(documentWriter)
        .updateExtraction(eq(documentId), eq(NEW_DOC_TYPE_ID), fieldsCaptor.capture());
    Map<String, Object> persistedFields = fieldsCaptor.getValue();
    assertThat(persistedFields).isEqualTo(newFields);
    assertThat(persistedFields).doesNotContainKey("vendor");
    verify(documentWriter).setReextractionStatus(documentId, ReextractionStatus.NONE);
    verify(documentWriter, never()).insert(any());

    ArgumentCaptor<DocumentEvent> eventCaptor = ArgumentCaptor.forClass(DocumentEvent.class);
    verify(eventBus).publish(eventCaptor.capture());
    assertThat(eventCaptor.getValue()).isInstanceOf(ExtractionCompleted.class);
    ExtractionCompleted completed = (ExtractionCompleted) eventCaptor.getValue();
    assertThat(completed.documentId()).isEqualTo(documentId);
    assertThat(completed.detectedDocumentType()).isEqualTo(NEW_DOC_TYPE_ID);
    assertThat(completed.extractedFields()).isEqualTo(newFields);
    assertThat(completed.organizationId()).isEqualTo(ORG_ID);
  }

  @Test
  void retypeAuditRowCarriesDocumentIdAndNullProcessingDocumentId() {
    UUID documentId = UUID.randomUUID();
    UUID storedId = UUID.randomUUID();
    Document document =
        new Document(
            documentId,
            storedId,
            ORG_ID,
            DOC_TYPE_ID,
            Map.of(),
            RAW_TEXT,
            Instant.parse("2026-04-27T10:00:00Z"),
            ReextractionStatus.NONE);
    when(documentReader.get(documentId)).thenReturn(Optional.of(document));
    when(documentWriter.claimReextractionInProgress(documentId)).thenReturn(true);
    when(executor.execute(any(MessageCreateParams.class), any(ToolSchema.class)))
        .thenReturn(JsonValue.from(Map.of("merchant", "X")));

    extractor.extract(documentId, NEW_DOC_TYPE_ID);

    ArgumentCaptor<LlmCallAudit> captor = ArgumentCaptor.forClass(LlmCallAudit.class);
    verify(auditWriter).insert(captor.capture());
    LlmCallAudit audit = captor.getValue();
    assertThat(audit.documentId()).isEqualTo(documentId);
    assertThat(audit.processingDocumentId()).isNull();
    assertThat(audit.storedDocumentId().value()).isEqualTo(storedId);
    assertThat(audit.organizationId()).isEqualTo(ORG_ID);
    assertThat(audit.callType()).isEqualTo(CallType.EXTRACT);
    assertThat(audit.modelId()).isEqualTo(MODEL_ID);
    assertThat(audit.error()).isNull();
  }

  @Test
  void retypeRejectsWhenReextractionAlreadyInProgress() {
    UUID documentId = UUID.randomUUID();
    UUID storedId = UUID.randomUUID();
    Document document =
        new Document(
            documentId,
            storedId,
            ORG_ID,
            DOC_TYPE_ID,
            Map.of(),
            RAW_TEXT,
            Instant.parse("2026-04-27T10:00:00Z"),
            ReextractionStatus.IN_PROGRESS);
    when(documentReader.get(documentId)).thenReturn(Optional.of(document));
    when(documentWriter.claimReextractionInProgress(documentId)).thenReturn(false);

    assertThatThrownBy(() -> extractor.extract(documentId, NEW_DOC_TYPE_ID))
        .isInstanceOf(RetypeAlreadyInProgressException.class);

    verify(documentWriter).claimReextractionInProgress(documentId);
    verify(executor, never()).execute(any(), any());
    verify(auditWriter, never()).insert(any());
    verify(documentWriter, never()).setReextractionStatus(any(), any());
    verify(documentWriter, never()).updateExtraction(any(), any(), any());
    verify(eventBus, never()).publish(any());
  }

  @Test
  void retypeFailureAfterRetryPublishesExtractionFailedAndSetsFailedStatus() {
    UUID documentId = UUID.randomUUID();
    UUID storedId = UUID.randomUUID();
    Document document =
        new Document(
            documentId,
            storedId,
            ORG_ID,
            DOC_TYPE_ID,
            Map.of("vendor", "Old"),
            RAW_TEXT,
            Instant.parse("2026-04-27T10:00:00Z"),
            ReextractionStatus.NONE);
    when(documentReader.get(documentId)).thenReturn(Optional.of(document));
    when(documentWriter.claimReextractionInProgress(documentId)).thenReturn(true);
    when(executor.execute(any(MessageCreateParams.class), any(ToolSchema.class)))
        .thenThrow(new LlmSchemaViolation("first"))
        .thenThrow(new LlmSchemaViolation("second"));

    assertThatThrownBy(() -> extractor.extract(documentId, NEW_DOC_TYPE_ID))
        .isInstanceOf(LlmSchemaViolation.class)
        .hasMessage("LlmCall failed: LlmSchemaViolation");

    verify(documentWriter).claimReextractionInProgress(documentId);
    verify(documentWriter).setReextractionStatus(documentId, ReextractionStatus.FAILED);
    verify(documentWriter, never()).updateExtraction(any(), any(), any());
    verify(executor, times(2)).execute(any(), any());
    verify(auditWriter, times(2)).insert(any());

    ArgumentCaptor<DocumentEvent> eventCaptor = ArgumentCaptor.forClass(DocumentEvent.class);
    verify(eventBus).publish(eventCaptor.capture());
    assertThat(eventCaptor.getValue()).isInstanceOf(ExtractionFailed.class);
    ExtractionFailed failed = (ExtractionFailed) eventCaptor.getValue();
    assertThat(failed.documentId()).isEqualTo(documentId);
    assertThat(failed.error()).isEqualTo("LlmCall failed: LlmSchemaViolation");
    assertThat(failed.error()).doesNotContain("second");
    assertThat(failed.organizationId()).isEqualTo(ORG_ID);
  }

  @Test
  void redactsDocumentTextFromAuditAndRethrownExceptionMessage() {
    UUID storedId = UUID.randomUUID();
    UUID procId = UUID.randomUUID();
    String sensitive = "INVOICE FROM ACME — total $1234";
    when(executor.execute(any(MessageCreateParams.class), any(ToolSchema.class)))
        .thenThrow(new LlmProtocolError(sensitive));

    assertThatThrownBy(
            () -> extractor.extractFields(storedId, procId, ORG_ID, DOC_TYPE_ID, RAW_TEXT))
        .isInstanceOf(LlmProtocolError.class)
        .extracting(Throwable::getMessage)
        .asString()
        .doesNotContain(sensitive)
        .isEqualTo("LlmCall failed: LlmProtocolError");

    ArgumentCaptor<LlmCallAudit> captor = ArgumentCaptor.forClass(LlmCallAudit.class);
    verify(auditWriter).insert(captor.capture());
    LlmCallAudit audit = captor.getValue();
    assertThat(audit.error()).doesNotContain(sensitive);
    assertThat(audit.error()).isEqualTo("LlmCall failed: LlmProtocolError");
  }

  @Test
  void redactsDocumentTextFromExtractionFailedEventOnRetypePath() {
    UUID documentId = UUID.randomUUID();
    UUID storedId = UUID.randomUUID();
    String sensitive = "INVOICE FROM ACME — total $1234";
    Document document =
        new Document(
            documentId,
            storedId,
            ORG_ID,
            DOC_TYPE_ID,
            Map.of(),
            RAW_TEXT,
            Instant.parse("2026-04-27T10:00:00Z"),
            ReextractionStatus.NONE);
    when(documentReader.get(documentId)).thenReturn(Optional.of(document));
    when(documentWriter.claimReextractionInProgress(documentId)).thenReturn(true);
    when(executor.execute(any(MessageCreateParams.class), any(ToolSchema.class)))
        .thenThrow(new LlmTimeout(sensitive));

    assertThatThrownBy(() -> extractor.extract(documentId, NEW_DOC_TYPE_ID))
        .isInstanceOf(LlmTimeout.class)
        .extracting(Throwable::getMessage)
        .asString()
        .doesNotContain(sensitive)
        .isEqualTo("LlmCall failed: LlmTimeout");

    ArgumentCaptor<DocumentEvent> eventCaptor = ArgumentCaptor.forClass(DocumentEvent.class);
    verify(eventBus).publish(eventCaptor.capture());
    ExtractionFailed failed = (ExtractionFailed) eventCaptor.getValue();
    assertThat(failed.error()).doesNotContain(sensitive);
    assertThat(failed.error()).isEqualTo("LlmCall failed: LlmTimeout");

    ArgumentCaptor<LlmCallAudit> auditCaptor = ArgumentCaptor.forClass(LlmCallAudit.class);
    verify(auditWriter).insert(auditCaptor.capture());
    assertThat(auditCaptor.getValue().error()).doesNotContain(sensitive);
    assertThat(auditCaptor.getValue().error()).isEqualTo("LlmCall failed: LlmTimeout");
  }

  @SuppressWarnings({
    "unchecked",
    "rawtypes"
  }) // why: Mockito ArgumentCaptor + generic Map needs raw->cast.
  private static ArgumentCaptor<Map<String, Object>> mapCaptor() {
    return ArgumentCaptor.forClass((Class) Map.class);
  }
}
