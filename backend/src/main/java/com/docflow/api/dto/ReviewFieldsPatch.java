package com.docflow.api.dto;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record ReviewFieldsPatch(@NotNull Map<String, Object> extractedFields) {}
