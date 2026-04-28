package com.docflow.ingestion.storage;

import com.docflow.ingestion.StoredDocumentId;

public final class StoredFileNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final StoredDocumentId id;

  public StoredFileNotFoundException(StoredDocumentId id) {
    super("Stored file not found for id: " + id);
    this.id = id;
  }

  public StoredDocumentId id() {
    return id;
  }
}
