package com.docflow.ingestion;

import java.util.UUID;

public interface StoredDocumentIngestionService {

  IngestionResult upload(
      UUID organizationId, String sourceFilename, String claimedContentType, byte[] bytes);
}
