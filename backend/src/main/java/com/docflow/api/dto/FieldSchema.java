package com.docflow.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

public record FieldSchema(
    String name,
    String type,
    boolean required,
    List<String> enumValues,
    @JsonInclude(JsonInclude.Include.NON_NULL) String format,
    List<FieldSchema> itemFields,
    @JsonInclude(JsonInclude.Include.NON_NULL) String layout,
    @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean multiline) {}
