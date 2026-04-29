package com.docflow.c3.pipeline;

import com.docflow.c3.events.ProcessingCompleted;
import com.docflow.c3.events.ProcessingStepChanged;
import com.docflow.ingestion.StoredDocument;
import com.docflow.ingestion.StoredDocumentId;
import com.docflow.ingestion.StoredDocumentReader;
import com.docflow.platform.DocumentEventBus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
final class ProcessingPipelineOrchestrator {

  private static final String FAILED_STEP = "FAILED";

  private final TextExtractStep textExtractStep;
  private final ClassifyStep classifyStep;
  private final ExtractStep extractStep;
  private final ProcessingDocumentWriter writer;
  private final StoredDocumentReader storedDocumentReader;
  private final DocumentEventBus eventBus;
  private final Clock clock;

  ProcessingPipelineOrchestrator(
      TextExtractStep textExtractStep,
      ClassifyStep classifyStep,
      ExtractStep extractStep,
      ProcessingDocumentWriter writer,
      StoredDocumentReader storedDocumentReader,
      DocumentEventBus eventBus,
      Clock clock) {
    this.textExtractStep = textExtractStep;
    this.classifyStep = classifyStep;
    this.extractStep = extractStep;
    this.writer = writer;
    this.storedDocumentReader = storedDocumentReader;
    this.eventBus = eventBus;
    this.clock = clock;
  }

  void run(StoredDocumentId storedDocumentId, ProcessingDocumentId processingDocumentId) {
    StoredDocument stored =
        storedDocumentReader
            .get(storedDocumentId)
            .orElseThrow(() -> new ProcessingDocumentNotFoundException(processingDocumentId));

    PipelineContext context =
        new PipelineContext(processingDocumentId, storedDocumentId, stored.organizationId());

    List<PipelineStep> steps = List.of(textExtractStep, classifyStep, extractStep);
    for (PipelineStep step : steps) {
      writer.updateStep(processingDocumentId, step.stepName());
      publishStepChanged(context, step.stepName(), null);

      StepResult result = step.execute(context);
      if (result instanceof StepResult.Failure failure) {
        writer.markFailed(processingDocumentId, failure.message());
        publishStepChanged(context, FAILED_STEP, failure.message());
        return;
      }
      if (TextExtractStep.STEP_NAME.equals(step.stepName()) && context.rawText() != null) {
        writer.updateRawText(processingDocumentId, context.rawText());
      }
    }

    eventBus.publish(
        new ProcessingCompleted(
            storedDocumentId.value(),
            processingDocumentId.value(),
            context.organizationId(),
            context.detectedDocumentType(),
            context.extractedFields(),
            context.rawText(),
            Instant.now(clock)));
  }

  private void publishStepChanged(PipelineContext context, String currentStep, String error) {
    eventBus.publish(
        new ProcessingStepChanged(
            context.storedDocumentId().value(),
            context.processingDocumentId().value(),
            context.organizationId(),
            currentStep,
            error,
            Instant.now(clock)));
  }
}
