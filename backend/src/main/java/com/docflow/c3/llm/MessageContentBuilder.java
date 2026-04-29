package com.docflow.c3.llm;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoice;
import com.anthropic.models.messages.ToolChoiceTool;
import com.anthropic.models.messages.ToolUnion;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Component
public class MessageContentBuilder {

  private static final String SCHEMA_PROPERTIES = "properties";
  private static final String SCHEMA_REQUIRED = "required";

  private final ObjectMapper mapper = JsonMapper.builder().build();

  public MessageCreateParams build(
      String modelId, String systemPrompt, ToolSchema toolSchema, int maxTokens, String text) {
    if (modelId == null || modelId.isBlank()) {
      throw new IllegalArgumentException("modelId must not be blank");
    }
    if (systemPrompt == null) {
      throw new IllegalArgumentException("systemPrompt must not be null");
    }
    if (toolSchema == null) {
      throw new IllegalArgumentException("toolSchema must not be null");
    }
    if (maxTokens <= 0) {
      throw new IllegalArgumentException("maxTokens must be positive");
    }

    List<ContentBlockParam> blocks = buildContentBlocks(text);
    Tool tool = buildTool(toolSchema);
    ToolChoice toolChoice =
        ToolChoice.ofTool(ToolChoiceTool.builder().name(toolSchema.toolName()).build());

    return MessageCreateParams.builder()
        .model(Model.of(modelId))
        .maxTokens(maxTokens)
        .system(systemPrompt)
        .toolChoice(toolChoice)
        .tools(List.of(ToolUnion.ofTool(tool)))
        .addUserMessageOfBlockParams(blocks)
        .build();
  }

  private static List<ContentBlockParam> buildContentBlocks(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("text must be non-blank");
    }
    TextBlockParam textBlock = TextBlockParam.builder().text(text).build();
    return List.of(ContentBlockParam.ofText(textBlock));
  }

  private Tool buildTool(ToolSchema toolSchema) {
    Map<String, Object> parsed = parseSchema(toolSchema.inputSchemaJson());
    Object propertiesNode = parsed.get(SCHEMA_PROPERTIES);
    Object requiredNode = parsed.get(SCHEMA_REQUIRED);

    Tool.InputSchema.Builder schemaBuilder = Tool.InputSchema.builder();

    if (propertiesNode instanceof Map<?, ?> propertiesMap) {
      Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
      for (Map.Entry<?, ?> entry : propertiesMap.entrySet()) {
        props.putAdditionalProperty(
            String.valueOf(entry.getKey()), JsonValue.from(entry.getValue()));
      }
      schemaBuilder.properties(props.build());
    }
    if (requiredNode instanceof List<?> requiredList) {
      schemaBuilder.required(requiredList.stream().map(String::valueOf).toList());
    }

    return Tool.builder().name(toolSchema.toolName()).inputSchema(schemaBuilder.build()).build();
  }

  private Map<String, Object> parseSchema(String json) {
    try {
      Map<String, Object> parsed = mapper.readValue(json, new TypeReference<>() {});
      return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("toolSchema.inputSchemaJson is not valid JSON", e);
    }
  }
}
