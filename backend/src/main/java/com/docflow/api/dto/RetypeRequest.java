package com.docflow.api.dto;

import jakarta.validation.constraints.NotBlank;

public record RetypeRequest(@NotBlank String newDocumentType) {}
