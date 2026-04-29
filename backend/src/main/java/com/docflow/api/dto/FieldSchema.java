package com.docflow.api.dto;

import java.util.List;

public record FieldSchema(
    String name,
    String type,
    boolean required,
    List<String> enumValues,
    List<FieldSchema> itemFields) {}
