package com.docflow.c3.pipeline;

import java.util.Optional;

public interface ProcessingDocumentReader {

  Optional<ProcessingDocument> get(ProcessingDocumentId id);
}
