package com.docflow.ingestion.storage;

import com.docflow.ingestion.StoredDocumentId;
import java.io.InputStream;

public interface StoredDocumentStorage {

  void save(StoredDocumentId id, byte[] bytes);

  byte[] load(StoredDocumentId id);

  InputStream openStream(StoredDocumentId id);

  long size(StoredDocumentId id);

  void delete(StoredDocumentId id);
}
