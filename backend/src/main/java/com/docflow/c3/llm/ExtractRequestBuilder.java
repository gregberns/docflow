package com.docflow.c3.llm;

import com.anthropic.models.messages.MessageCreateParams;
import com.docflow.c3.llm.MessageContentBuilder.InputModality;
import com.docflow.config.catalog.DocumentTypeSchemaView;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
final class ExtractRequestBuilder {

  private static final int EXTRACT_MAX_TOKENS = 2048;

  private final PromptLibrary promptLibrary;
  private final ToolSchemaBuilder toolSchemaBuilder;
  private final MessageContentBuilder messageContentBuilder;

  ExtractRequestBuilder(
      PromptLibrary promptLibrary,
      ToolSchemaBuilder toolSchemaBuilder,
      MessageContentBuilder messageContentBuilder) {
    this.promptLibrary = promptLibrary;
    this.toolSchemaBuilder = toolSchemaBuilder;
    this.messageContentBuilder = messageContentBuilder;
  }

  Built build(String modelId, DocumentTypeSchemaView schema, String rawText) {
    ToolSchema toolSchema = toolSchemaBuilder.buildExtractSchema(schema);
    String systemPrompt = promptLibrary.getExtract(schema.id()).render(Map.of());
    MessageCreateParams params =
        messageContentBuilder.build(
            modelId,
            systemPrompt,
            toolSchema,
            InputModality.TEXT,
            EXTRACT_MAX_TOKENS,
            null,
            rawText);
    return new Built(params, toolSchema);
  }

  record Built(MessageCreateParams params, ToolSchema toolSchema) {}
}
