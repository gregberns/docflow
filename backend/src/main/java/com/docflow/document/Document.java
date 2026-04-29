package com.docflow.document;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public record Document(
    UUID id,
    UUID storedDocumentId,
    String organizationId,
    String detectedDocumentType,
    Map<String, Object> extractedFields,
    String rawText,
    Instant processedAt,
    ReextractionStatus reextractionStatus) {

  public Document {
    extractedFields =
        extractedFields == null ? Map.of() : Map.copyOf(new HashMap<>(extractedFields));
  }
}
