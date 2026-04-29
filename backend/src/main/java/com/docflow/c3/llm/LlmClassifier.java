package com.docflow.c3.llm;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.docflow.c3.audit.CallType;
import com.docflow.c3.audit.LlmCallAudit;
import com.docflow.c3.audit.LlmCallAuditId;
import com.docflow.c3.audit.LlmCallAuditWriter;
import com.docflow.config.AppConfig;
import com.docflow.config.catalog.OrganizationCatalog;
import com.docflow.ingestion.StoredDocumentId;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class LlmClassifier {

  private static final int CLASSIFY_MAX_TOKENS = 512;
  private static final String ALLOWED_DOC_TYPES_PLACEHOLDER = "ALLOWED_DOC_TYPES";
  private static final String DOC_TYPE_FIELD = "docType";

  private final OrganizationCatalog organizationCatalog;
  private final PromptLibrary promptLibrary;
  private final ToolSchemaBuilder toolSchemaBuilder;
  private final MessageContentBuilder messageContentBuilder;
  private final LlmCallExecutor executor;
  private final LlmCallAuditWriter auditWriter;
  private final String modelId;
  private final Clock clock;

  public LlmClassifier(
      OrganizationCatalog organizationCatalog,
      PromptLibrary promptLibrary,
      ToolSchemaBuilder toolSchemaBuilder,
      MessageContentBuilder messageContentBuilder,
      LlmCallExecutor executor,
      LlmCallAuditWriter auditWriter,
      AppConfig appConfig,
      Clock clock) {
    this.organizationCatalog = organizationCatalog;
    this.promptLibrary = promptLibrary;
    this.toolSchemaBuilder = toolSchemaBuilder;
    this.messageContentBuilder = messageContentBuilder;
    this.executor = executor;
    this.auditWriter = auditWriter;
    this.modelId = appConfig.llm().modelId();
    this.clock = clock;
  }

  public ClassifyResult classify(
      UUID storedDocumentId, UUID processingDocumentId, String organizationId, String rawText) {
    Objects.requireNonNull(storedDocumentId, "storedDocumentId");
    Objects.requireNonNull(processingDocumentId, "processingDocumentId");
    if (organizationId == null || organizationId.isBlank()) {
      throw new IllegalArgumentException("organizationId must not be blank");
    }
    if (rawText == null || rawText.isBlank()) {
      throw new IllegalArgumentException("rawText must not be blank");
    }

    List<String> allowed = organizationCatalog.getAllowedDocTypes(organizationId);
    ToolSchema toolSchema = toolSchemaBuilder.buildClassifySchema(organizationId, allowed);

    String systemPrompt =
        promptLibrary
            .getClassify()
            .render(Map.of(ALLOWED_DOC_TYPES_PLACEHOLDER, String.join(", ", allowed)));

    MessageCreateParams params =
        messageContentBuilder.build(
            modelId, systemPrompt, toolSchema, CLASSIFY_MAX_TOKENS, rawText);

    String error = null;
    try {
      JsonValue input = executor.execute(params, toolSchema);
      String detected = readDocType(input);
      if (!allowed.contains(detected)) {
        throw new LlmSchemaViolation("classify result '" + detected + "' not in allowed enum");
      }
      return new ClassifyResult(detected);
    } catch (RuntimeException e) {
      error = e.getMessage();
      throw e;
    } finally {
      writeAudit(storedDocumentId, processingDocumentId, organizationId, error);
    }
  }

  private static String readDocType(JsonValue input) {
    @SuppressWarnings("unchecked") // SDK-side Jackson 2 deserialization to a Map<String,Object>.
    Map<String, Object> map = input.convert(Map.class);
    if (map == null) {
      throw new LlmSchemaViolation("classify tool input was empty");
    }
    Object value = map.get(DOC_TYPE_FIELD);
    if (!(value instanceof String s) || s.isBlank()) {
      throw new LlmSchemaViolation("classify tool input missing '" + DOC_TYPE_FIELD + "' field");
    }
    return s;
  }

  private void writeAudit(
      UUID storedDocumentId, UUID processingDocumentId, String organizationId, String error) {
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

  public record ClassifyResult(String detectedDocumentType) {}
}
