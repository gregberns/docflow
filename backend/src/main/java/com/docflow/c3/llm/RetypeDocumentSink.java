package com.docflow.c3.llm;

import com.docflow.document.DocumentReader;
import com.docflow.document.DocumentWriter;
import com.docflow.platform.DocumentEventBus;
import org.springframework.stereotype.Component;

@Component
final class RetypeDocumentSink {

  private final DocumentReader reader;
  private final DocumentWriter writer;
  private final DocumentEventBus eventBus;

  RetypeDocumentSink(DocumentReader reader, DocumentWriter writer, DocumentEventBus eventBus) {
    this.reader = reader;
    this.writer = writer;
    this.eventBus = eventBus;
  }

  DocumentReader reader() {
    return reader;
  }

  DocumentWriter writer() {
    return writer;
  }

  DocumentEventBus eventBus() {
    return eventBus;
  }
}
