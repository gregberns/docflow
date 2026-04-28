package com.docflow.config.org;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record ArrayItemSchema(@NotEmpty @Valid List<FieldDefinition> fields) {}
