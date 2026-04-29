package com.docflow.c3.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.docflow.c3.llm.LlmClassifier.ClassifyResult;
import com.docflow.config.AppConfig;
import com.docflow.config.catalog.OrganizationCatalog;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LlmClassifierTest {

  private static final String ORG_ID = "riverside-bistro";
  private static final List<String> ALLOWED = List.of("invoice", "receipt", "expense-report");
  private static final String MODEL_ID = "claude-sonnet-4-6";
  private static final String CLASSIFY_PROMPT_RAW = "classify this; allowed: {{ALLOWED_DOC_TYPES}}";
  private static final ToolSchema TOOL_SCHEMA =
      new ToolSchema("select_doc_type", "{\"type\":\"object\"}");

  private OrganizationCatalog organizationCatalog;
  private PromptLibrary promptLibrary;
  private ToolSchemaBuilder toolSchemaBuilder;
  private MessageContentBuilder messageContentBuilder;
  private LlmCallExecutor executor;
  private LlmCallAuditWriter auditWriter;
  private LlmClassifier classifier;

  @BeforeEach
  void setUp() {
    organizationCatalog = mock(OrganizationCatalog.class);
    promptLibrary = mock(PromptLibrary.class);
    toolSchemaBuilder = mock(ToolSchemaBuilder.class);
    messageContentBuilder = mock(MessageContentBuilder.class);
    executor = mock(LlmCallExecutor.class);
    auditWriter = mock(LlmCallAuditWriter.class);

    when(organizationCatalog.getAllowedDocTypes(ORG_ID)).thenReturn(ALLOWED);
    when(toolSchemaBuilder.buildClassifySchema(ORG_ID, ALLOWED)).thenReturn(TOOL_SCHEMA);
    when(promptLibrary.getClassify()).thenReturn(new PromptTemplate(CLASSIFY_PROMPT_RAW));

    MessageCreateParams params =
        MessageCreateParams.builder().model(MODEL_ID).maxTokens(512).addUserMessage("x").build();
    when(messageContentBuilder.build(
            any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
        .thenReturn(params);

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

    classifier =
        new LlmClassifier(
            organizationCatalog,
            promptLibrary,
            toolSchemaBuilder,
            messageContentBuilder,
            executor,
            auditWriter,
            appConfig,
            clock);
  }

  @Test
  void successPathReturnsDetectedDocTypeAndWritesAuditWithNoError() {
    UUID storedId = UUID.randomUUID();
    UUID procId = UUID.randomUUID();
    when(executor.execute(any(MessageCreateParams.class), any(ToolSchema.class)))
        .thenReturn(JsonValue.from(Map.of("docType", "invoice")));

    ClassifyResult result = classifier.classify(storedId, procId, ORG_ID, "raw text body");

    assertThat(result.detectedDocumentType()).isEqualTo("invoice");

    ArgumentCaptor<LlmCallAudit> captor = ArgumentCaptor.forClass(LlmCallAudit.class);
    verify(auditWriter, times(1)).insert(captor.capture());
    LlmCallAudit audit = captor.getValue();
    assertThat(audit.error()).isNull();
    assertThat(audit.processingDocumentId()).isEqualTo(procId);
    assertThat(audit.documentId()).isNull();
    assertThat(audit.storedDocumentId().value()).isEqualTo(storedId);
    assertThat(audit.organizationId()).isEqualTo(ORG_ID);
    assertThat(audit.callType()).isEqualTo(CallType.CLASSIFY);
    assertThat(audit.modelId()).isEqualTo(MODEL_ID);

    verify(executor, times(1)).execute(any(MessageCreateParams.class), any(ToolSchema.class));
  }

  @Test
  void schemaViolationWhenModelReturnsNonAllowedValueAndStillWritesAudit() {
    UUID storedId = UUID.randomUUID();
    UUID procId = UUID.randomUUID();
    when(executor.execute(any(MessageCreateParams.class), any(ToolSchema.class)))
        .thenReturn(JsonValue.from(Map.of("docType", "unknown_type")));

    assertThatThrownBy(() -> classifier.classify(storedId, procId, ORG_ID, "raw text body"))
        .isInstanceOf(LlmSchemaViolation.class)
        .hasMessageContaining("unknown_type");

    ArgumentCaptor<LlmCallAudit> captor = ArgumentCaptor.forClass(LlmCallAudit.class);
    verify(auditWriter, times(1)).insert(captor.capture());
    LlmCallAudit audit = captor.getValue();
    assertThat(audit.error()).contains("unknown_type");
    assertThat(audit.processingDocumentId()).isEqualTo(procId);
    assertThat(audit.documentId()).isNull();

    verify(executor, times(1)).execute(any(MessageCreateParams.class), any(ToolSchema.class));
  }

  @Test
  void sdkFailurePropagatesAndWritesAuditWithErrorMessage() {
    UUID storedId = UUID.randomUUID();
    UUID procId = UUID.randomUUID();
    when(executor.execute(any(MessageCreateParams.class), any(ToolSchema.class)))
        .thenThrow(new LlmUnavailable("anthropic service returned status 503"));

    assertThatThrownBy(() -> classifier.classify(storedId, procId, ORG_ID, "raw text body"))
        .isInstanceOf(LlmUnavailable.class)
        .hasMessageContaining("503");

    ArgumentCaptor<LlmCallAudit> captor = ArgumentCaptor.forClass(LlmCallAudit.class);
    verify(auditWriter, times(1)).insert(captor.capture());
    LlmCallAudit audit = captor.getValue();
    assertThat(audit.error()).contains("503");
    assertThat(audit.processingDocumentId()).isEqualTo(procId);

    verify(executor, times(1)).execute(any(MessageCreateParams.class), any(ToolSchema.class));
  }

  @Test
  void blankRawTextRejectedBeforeAnySdkCall() {
    UUID storedId = UUID.randomUUID();
    UUID procId = UUID.randomUUID();

    assertThatThrownBy(() -> classifier.classify(storedId, procId, ORG_ID, "  "))
        .isInstanceOf(IllegalArgumentException.class);

    verify(executor, never()).execute(any(), any());
    verify(auditWriter, never()).insert(any());
  }

  @Test
  void blankOrganizationIdRejectedBeforeAnySdkCall() {
    UUID storedId = UUID.randomUUID();
    UUID procId = UUID.randomUUID();

    assertThatThrownBy(() -> classifier.classify(storedId, procId, "", "raw text"))
        .isInstanceOf(IllegalArgumentException.class);

    verify(executor, never()).execute(any(), any());
    verify(auditWriter, never()).insert(any());
  }
}
