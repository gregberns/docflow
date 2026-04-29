package com.docflow.c3.pipeline;

import com.docflow.c3.llm.LlmExtractor;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
final class ExtractStep implements PipelineStep {

  static final String STEP_NAME = "EXTRACTING";

  private final LlmExtractor extractor;

  ExtractStep(LlmExtractor extractor) {
    this.extractor = extractor;
  }

  @Override
  public String stepName() {
    return STEP_NAME;
  }

  @Override
  public StepResult execute(PipelineContext context) {
    try {
      Map<String, Object> fields =
          extractor.extractFields(
              context.storedDocumentId().value(),
              context.processingDocumentId().value(),
              context.organizationId(),
              context.detectedDocumentType(),
              context.rawText());
      context.setExtractedFields(fields);
      return new StepResult.Success();
    } catch (RuntimeException e) {
      return new StepResult.Failure("extract failed: " + e.getMessage());
    }
  }
}
