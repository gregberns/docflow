package com.docflow.ingestion;

public interface StoredDocumentWriter {

  void insert(StoredDocument document);
}
