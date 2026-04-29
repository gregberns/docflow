package com.docflow.c3.pipeline;

import com.docflow.c3.llm.LlmClassifier;
import org.springframework.stereotype.Component;

@Component
final class ClassifyStep implements PipelineStep {

  static final String STEP_NAME = "CLASSIFYING";

  private final LlmClassifier classifier;

  ClassifyStep(LlmClassifier classifier) {
    this.classifier = classifier;
  }

  @Override
  public String stepName() {
    return STEP_NAME;
  }

  @Override
  public StepResult execute(PipelineContext context) {
    try {
      LlmClassifier.ClassifyResult result =
          classifier.classify(
              context.storedDocumentId().value(),
              context.processingDocumentId().value(),
              context.organizationId(),
              context.rawText());
      context.setDetectedDocumentType(result.detectedDocumentType());
      return new StepResult.Success();
    } catch (RuntimeException e) {
      return new StepResult.Failure("classify failed: " + e.getMessage());
    }
  }
}
