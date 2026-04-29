package com.docflow.c3.llm;

import com.docflow.config.catalog.DocumentTypeSchemaView;
import com.docflow.config.catalog.FieldView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Component
public final class ToolSchemaBuilder {

  static final String CLASSIFY_TOOL_NAME = "select_doc_type";
  static final String EXTRACT_TOOL_PREFIX = "extract_";
  static final String CLASSIFY_FIELD = "docType";

  private static final String TYPE = "type";
  private static final String PROPERTIES = "properties";
  private static final String REQUIRED = "required";
  private static final String ENUM = "enum";
  private static final String ITEMS = "items";
  private static final String OBJECT = "object";
  private static final String STRING = "string";
  private static final String NUMBER = "number";
  private static final String BOOLEAN = "boolean";
  private static final String ARRAY = "array";

  private final ObjectMapper mapper =
      JsonMapper.builder().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, false).build();

  public ToolSchema buildClassifySchema(String orgId, List<String> allowedDocTypes) {
    if (orgId == null || orgId.isBlank()) {
      throw new IllegalArgumentException("orgId must not be blank");
    }
    if (allowedDocTypes == null || allowedDocTypes.isEmpty()) {
      throw new IllegalArgumentException(
          "allowedDocTypes must not be empty for org '" + orgId + "'");
    }

    Map<String, Object> docTypeProperty = new LinkedHashMap<>();
    docTypeProperty.put(TYPE, STRING);
    docTypeProperty.put(ENUM, List.copyOf(allowedDocTypes));

    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(CLASSIFY_FIELD, docTypeProperty);

    Map<String, Object> root = new LinkedHashMap<>();
    root.put(TYPE, OBJECT);
    root.put(PROPERTIES, properties);
    root.put(REQUIRED, List.of(CLASSIFY_FIELD));

    return new ToolSchema(CLASSIFY_TOOL_NAME, writeJson(root));
  }

  public ToolSchema buildExtractSchema(DocumentTypeSchemaView docType) {
    if (docType == null) {
      throw new IllegalArgumentException("docType must not be null");
    }
    String toolName = EXTRACT_TOOL_PREFIX + docType.id();
    Map<String, Object> root = buildObjectSchema(docType.fields());
    return new ToolSchema(toolName, writeJson(root));
  }

  private Map<String, Object> buildObjectSchema(List<FieldView> fields) {
    Map<String, Object> properties = new LinkedHashMap<>();
    List<String> required = new ArrayList<>();
    for (FieldView field : fields) {
      properties.put(field.name(), buildFieldSchema(field));
      if (field.required()) {
        required.add(field.name());
      }
    }
    Map<String, Object> object = new LinkedHashMap<>();
    object.put(TYPE, OBJECT);
    object.put(PROPERTIES, properties);
    object.put(REQUIRED, List.copyOf(required));
    return object;
  }

  @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
  private Map<String, Object> buildFieldSchema(FieldView field) {
    String fieldType = field.type() == null ? "" : field.type().toUpperCase(Locale.ROOT);
    Map<String, Object> schema = new LinkedHashMap<>();
    switch (fieldType) {
      case "STRING", "DATE" -> schema.put(TYPE, STRING);
      case "DECIMAL", "NUMBER" -> schema.put(TYPE, NUMBER);
      case "BOOLEAN" -> schema.put(TYPE, BOOLEAN);
      case "ENUM" -> {
        schema.put(TYPE, STRING);
        List<String> values = field.enumValues();
        if (values == null || values.isEmpty()) {
          throw new IllegalArgumentException(
              "ENUM field '" + field.name() + "' must declare enumValues");
        }
        schema.put(ENUM, List.copyOf(values));
      }
      case "ARRAY" -> {
        schema.put(TYPE, ARRAY);
        List<FieldView> itemFields = field.itemFields();
        if (itemFields == null || itemFields.isEmpty()) {
          throw new IllegalArgumentException(
              "ARRAY field '" + field.name() + "' must declare itemFields");
        }
        schema.put(ITEMS, buildObjectSchema(itemFields));
      }
      default ->
          throw new IllegalArgumentException(
              "unsupported field type '" + field.type() + "' for field '" + field.name() + "'");
    }
    return schema;
  }

  private String writeJson(Map<String, Object> root) {
    try {
      return mapper.writeValueAsString(root);
    } catch (RuntimeException e) {
      throw new IllegalStateException("failed to serialize tool schema", e);
    }
  }
}
