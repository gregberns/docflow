package com.docflow.document;

import java.util.Optional;
import java.util.UUID;

public interface DocumentReader {

  Optional<Document> get(UUID documentId);
}
