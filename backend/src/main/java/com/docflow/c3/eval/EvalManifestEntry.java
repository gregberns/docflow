package com.docflow.c3.eval;

import java.util.Map;

public record EvalManifestEntry(
    String path, String organizationId, String documentType, Map<String, Object> extractedFields) {

  public EvalManifestEntry {
    extractedFields = extractedFields == null ? Map.of() : Map.copyOf(extractedFields);
  }
}
