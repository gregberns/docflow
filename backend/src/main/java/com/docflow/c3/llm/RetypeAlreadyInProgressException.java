package com.docflow.c3.llm;

import java.util.UUID;

public final class RetypeAlreadyInProgressException extends RuntimeException {

  private final UUID documentId;

  public RetypeAlreadyInProgressException(UUID documentId) {
    super("retype already in progress for document " + documentId);
    this.documentId = documentId;
  }

  public UUID documentId() {
    return documentId;
  }
}
