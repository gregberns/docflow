package com.docflow.ingestion.storage;

import com.docflow.ingestion.StoredDocumentId;

public interface StoredDocumentStorage {

  void save(StoredDocumentId id, byte[] bytes);

  byte[] load(StoredDocumentId id);

  void delete(StoredDocumentId id);
}
