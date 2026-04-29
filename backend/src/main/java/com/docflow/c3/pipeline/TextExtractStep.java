package com.docflow.c3.pipeline;

import com.docflow.ingestion.storage.StoredDocumentStorage;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

@Component
final class TextExtractStep implements PipelineStep {

  static final String STEP_NAME = "TEXT_EXTRACTING";

  private final StoredDocumentStorage storage;

  TextExtractStep(StoredDocumentStorage storage) {
    this.storage = storage;
  }

  @Override
  public String stepName() {
    return STEP_NAME;
  }

  @Override
  public StepResult execute(PipelineContext context) {
    byte[] bytes;
    try {
      bytes = storage.load(context.storedDocumentId());
    } catch (RuntimeException e) {
      return new StepResult.Failure("text-extract failed: " + e.getMessage());
    }

    try (PDDocument document = Loader.loadPDF(bytes)) {
      PDFTextStripper stripper = new PDFTextStripper();
      String text = stripper.getText(document);
      context.setRawText(text);
      return new StepResult.Success();
    } catch (RuntimeException | java.io.IOException e) {
      return new StepResult.Failure("text-extract failed: " + e.getMessage());
    }
  }
}
