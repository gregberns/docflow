package com.docflow.c3.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.docflow.c3.events.ProcessingCompleted;
import com.docflow.c3.events.ProcessingStepChanged;
import com.docflow.c3.llm.LlmClassifier;
import com.docflow.c3.llm.LlmExtractor;
import com.docflow.c3.llm.LlmUnavailable;
import com.docflow.ingestion.StoredDocument;
import com.docflow.ingestion.StoredDocumentId;
import com.docflow.ingestion.StoredDocumentReader;
import com.docflow.ingestion.storage.StoredDocumentStorage;
import com.docflow.platform.DocumentEvent;
import com.docflow.platform.DocumentEventBus;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProcessingPipelineOrchestratorTest {

  private static final String ORG_ID = "riverside-bistro";
  private static final String DETECTED_DOC_TYPE = "invoice";

  private StoredDocumentStorage storage;
  private LlmClassifier classifier;
  private LlmExtractor extractor;
  private ProcessingDocumentWriter writer;
  private StoredDocumentReader storedDocumentReader;
  private DocumentEventBus eventBus;
  private ProcessingPipelineOrchestrator orchestrator;

  private StoredDocumentId storedDocumentId;
  private ProcessingDocumentId processingDocumentId;
  private List<DocumentEvent> publishedEvents;
  private List<String> stepWrites;

  @BeforeEach
  void setUp() throws Exception {
    storage = mock(StoredDocumentStorage.class);
    classifier = mock(LlmClassifier.class);
    extractor = mock(LlmExtractor.class);
    writer = mock(ProcessingDocumentWriter.class);
    storedDocumentReader = mock(StoredDocumentReader.class);
    eventBus = mock(DocumentEventBus.class);

    Clock clock = Clock.fixed(Instant.parse("2026-04-28T12:00:00Z"), ZoneOffset.UTC);

    TextExtractStep textExtractStep = new TextExtractStep(storage);
    ClassifyStep classifyStep = new ClassifyStep(classifier);
    ExtractStep extractStep = new ExtractStep(extractor);

    orchestrator =
        new ProcessingPipelineOrchestrator(
            textExtractStep,
            classifyStep,
            extractStep,
            writer,
            storedDocumentReader,
            eventBus,
            clock);

    storedDocumentId = StoredDocumentId.generate();
    processingDocumentId = ProcessingDocumentId.generate();

    when(storedDocumentReader.get(storedDocumentId))
        .thenReturn(
            Optional.of(
                new StoredDocument(
                    storedDocumentId,
                    ORG_ID,
                    Instant.parse("2026-04-28T11:00:00Z"),
                    "doc.pdf",
                    "application/pdf",
                    "/tmp/doc.bin")));

    when(storage.load(storedDocumentId)).thenReturn(buildPdfWithText("Sample invoice text"));

    publishedEvents = new ArrayList<>();
    doAnswer(
            inv -> {
              publishedEvents.add(inv.getArgument(0, DocumentEvent.class));
              return null;
            })
        .when(eventBus)
        .publish(any(DocumentEvent.class));

    stepWrites = new ArrayList<>();
    doAnswer(
            inv -> {
              stepWrites.add(inv.getArgument(1, String.class));
              return null;
            })
        .when(writer)
        .updateStep(eq(processingDocumentId), any(String.class));
  }

  @Test
  void happyPathRunsAllThreeStepsAndEmitsCompleted() {
    when(classifier.classify(
            eq(storedDocumentId.value()),
            eq(processingDocumentId.value()),
            eq(ORG_ID),
            any(String.class)))
        .thenReturn(new LlmClassifier.ClassifyResult(DETECTED_DOC_TYPE));
    Map<String, Object> fields = Map.of("vendor", "Acme");
    when(extractor.extractFields(
            eq(storedDocumentId.value()),
            eq(processingDocumentId.value()),
            eq(ORG_ID),
            eq(DETECTED_DOC_TYPE),
            any(String.class)))
        .thenReturn(fields);

    orchestrator.run(storedDocumentId, processingDocumentId);

    assertThat(stepWrites)
        .containsExactly(TextExtractStep.STEP_NAME, ClassifyStep.STEP_NAME, ExtractStep.STEP_NAME);

    verify(writer, times(1)).updateRawText(eq(processingDocumentId), any(String.class));
    verify(writer, never()).markFailed(any(), any());

    List<ProcessingStepChanged> stepEvents =
        publishedEvents.stream()
            .filter(ProcessingStepChanged.class::isInstance)
            .map(ProcessingStepChanged.class::cast)
            .toList();
    assertThat(stepEvents).hasSize(3);
    assertThat(stepEvents.get(0).currentStep()).isEqualTo(TextExtractStep.STEP_NAME);
    assertThat(stepEvents.get(1).currentStep()).isEqualTo(ClassifyStep.STEP_NAME);
    assertThat(stepEvents.get(2).currentStep()).isEqualTo(ExtractStep.STEP_NAME);
    assertThat(stepEvents).allMatch(e -> e.error() == null);

    List<ProcessingCompleted> completed =
        publishedEvents.stream()
            .filter(ProcessingCompleted.class::isInstance)
            .map(ProcessingCompleted.class::cast)
            .toList();
    assertThat(completed).hasSize(1);
    ProcessingCompleted event = completed.get(0);
    assertThat(event.detectedDocumentType()).isEqualTo(DETECTED_DOC_TYPE);
    assertThat(event.extractedFields()).isEqualTo(fields);
    assertThat(event.organizationId()).isEqualTo(ORG_ID);
    assertThat(event.storedDocumentId()).isEqualTo(storedDocumentId.value());
    assertThat(event.processingDocumentId()).isEqualTo(processingDocumentId.value());
  }

  @Test
  void textExtractFailureMarksFailedAndSkipsLlmCalls() {
    when(storage.load(storedDocumentId)).thenReturn("not a pdf".getBytes(StandardCharsets.UTF_8));

    orchestrator.run(storedDocumentId, processingDocumentId);

    ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
    verify(writer, times(1)).markFailed(eq(processingDocumentId), errorCaptor.capture());
    assertThat(errorCaptor.getValue()).startsWith("text-extract failed:");

    verify(classifier, never()).classify(any(), any(), any(), any());
    verify(extractor, never()).extractFields(any(), any(), any(), any(), any());

    List<ProcessingStepChanged> stepEvents =
        publishedEvents.stream()
            .filter(ProcessingStepChanged.class::isInstance)
            .map(ProcessingStepChanged.class::cast)
            .toList();
    assertThat(stepEvents).hasSize(2);
    assertThat(stepEvents.get(0).currentStep()).isEqualTo(TextExtractStep.STEP_NAME);
    assertThat(stepEvents.get(1).currentStep()).isEqualTo("FAILED");
    assertThat(stepEvents.get(1).error()).startsWith("text-extract failed:");

    assertThat(publishedEvents.stream().anyMatch(ProcessingCompleted.class::isInstance)).isFalse();
  }

  @Test
  void classifyFailureMarksFailedAndSkipsExtract() {
    when(classifier.classify(
            any(UUID.class), any(UUID.class), any(String.class), any(String.class)))
        .thenThrow(new LlmUnavailable("anthropic service returned status 503"));

    orchestrator.run(storedDocumentId, processingDocumentId);

    ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
    verify(writer, times(1)).markFailed(eq(processingDocumentId), errorCaptor.capture());
    assertThat(errorCaptor.getValue()).startsWith("classify failed:");
    assertThat(errorCaptor.getValue()).contains("503");

    verify(extractor, never()).extractFields(any(), any(), any(), any(), any());

    List<ProcessingStepChanged> stepEvents =
        publishedEvents.stream()
            .filter(ProcessingStepChanged.class::isInstance)
            .map(ProcessingStepChanged.class::cast)
            .toList();
    assertThat(stepEvents).hasSize(3);
    assertThat(stepEvents.get(0).currentStep()).isEqualTo(TextExtractStep.STEP_NAME);
    assertThat(stepEvents.get(1).currentStep()).isEqualTo(ClassifyStep.STEP_NAME);
    assertThat(stepEvents.get(2).currentStep()).isEqualTo("FAILED");
    assertThat(stepEvents.get(2).error()).startsWith("classify failed:");

    assertThat(publishedEvents.stream().anyMatch(ProcessingCompleted.class::isInstance)).isFalse();
  }

  @Test
  void extractFailureMarksFailed() {
    when(classifier.classify(
            any(UUID.class), any(UUID.class), any(String.class), any(String.class)))
        .thenReturn(new LlmClassifier.ClassifyResult(DETECTED_DOC_TYPE));
    when(extractor.extractFields(
            any(UUID.class),
            any(UUID.class),
            any(String.class),
            any(String.class),
            any(String.class)))
        .thenThrow(new LlmUnavailable("anthropic timeout"));

    orchestrator.run(storedDocumentId, processingDocumentId);

    ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
    verify(writer, times(1)).markFailed(eq(processingDocumentId), errorCaptor.capture());
    assertThat(errorCaptor.getValue()).startsWith("extract failed:");

    List<ProcessingStepChanged> stepEvents =
        publishedEvents.stream()
            .filter(ProcessingStepChanged.class::isInstance)
            .map(ProcessingStepChanged.class::cast)
            .toList();
    assertThat(stepEvents).hasSize(4);
    assertThat(stepEvents.get(3).currentStep()).isEqualTo("FAILED");
    assertThat(stepEvents.get(3).error()).startsWith("extract failed:");

    assertThat(publishedEvents.stream().anyMatch(ProcessingCompleted.class::isInstance)).isFalse();
  }

  private static byte[] buildPdfWithText(String text) throws Exception {
    try (PDDocument doc = new PDDocument();
        ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
      PDPage page = new PDPage();
      doc.addPage(page);
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        cs.beginText();
        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        cs.newLineAtOffset(50, 700);
        cs.showText(text);
        cs.endText();
      }
      doc.save(baos);
      return baos.toByteArray();
    }
  }
}
