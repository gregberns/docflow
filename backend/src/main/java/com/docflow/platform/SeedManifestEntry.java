package com.docflow.platform;

import java.util.Map;

public record SeedManifestEntry(
    String path, String organizationId, String documentType, Map<String, Object> extractedFields) {

  public SeedManifestEntry {
    extractedFields = extractedFields == null ? Map.of() : Map.copyOf(extractedFields);
  }
}
