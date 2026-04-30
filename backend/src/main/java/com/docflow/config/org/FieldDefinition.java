package com.docflow.config.org;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record FieldDefinition(
    @NotBlank String name,
    @NotNull FieldType type,
    boolean required,
    List<String> enumValues,
    String format,
    @Valid ArrayItemSchema itemSchema) {}
