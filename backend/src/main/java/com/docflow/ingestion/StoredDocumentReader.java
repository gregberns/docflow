package com.docflow.ingestion;

import java.util.Optional;

public interface StoredDocumentReader {

  Optional<StoredDocument> get(StoredDocumentId id);
}
