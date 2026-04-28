package com.docflow.ingestion;

public interface StoredDocumentIngestionService {

  IngestionResult upload(
      String organizationId, String sourceFilename, String claimedContentType, byte[] bytes);
}
