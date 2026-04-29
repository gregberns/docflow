package com.docflow.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.docflow.c3.llm.LlmExtractor;
import com.docflow.c3.llm.RetypeAlreadyInProgressException;
import com.docflow.config.catalog.StageView;
import com.docflow.config.catalog.TransitionView;
import com.docflow.config.catalog.WorkflowCatalog;
import com.docflow.config.catalog.WorkflowView;
import com.docflow.document.Document;
import com.docflow.document.DocumentReader;
import com.docflow.document.ReextractionStatus;
import com.docflow.platform.DocumentEvent;
import com.docflow.platform.DocumentEventBus;
import com.docflow.workflow.events.DocumentStateChanged;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;

class WorkflowEngineExampleTest {

  private static final String ORG_ID = "riverside-bistro";
  private static final String DOC_TYPE_ID = "invoice";
  private static final String NEW_DOC_TYPE_ID = "receipt";

  private static final String STAGE_REVIEW = "stage-review";
  private static final String STAGE_MANAGER = "stage-manager";
  private static final String STAGE_FILED = "stage-filed";
  private static final String STAGE_REJECTED = "stage-rejected";

  private static final StageView REVIEW_STAGE =
      new StageView(STAGE_REVIEW, "Review", "review", "AWAITING_REVIEW", null);
  private static final StageView MANAGER_STAGE =
      new StageView(STAGE_MANAGER, "Manager Approval", "approval", "AWAITING_APPROVAL", "manager");
  private static final StageView FILED_STAGE =
      new StageView(STAGE_FILED, "Filed", "terminal", "FILED", null);
  private static final StageView REJECTED_STAGE =
      new StageView(STAGE_REJECTED, "Rejected", "terminal", "REJECTED", null);

  private static final Instant FIXED_NOW = Instant.parse("2026-04-28T12:00:00Z");

  private WorkflowCatalog catalog;
  private DocumentReader documentReader;
  private WorkflowInstanceReader instanceReader;
  private WorkflowInstanceWriter instanceWriter;
  private LlmExtractor llmExtractor;
  private DocumentEventBus eventBus;
  private WorkflowEngine engine;

  private UUID documentId;
  private UUID storedDocumentId;

  @BeforeEach
  void setUp() {
    catalog = mock(WorkflowCatalog.class);
    documentReader = mock(DocumentReader.class);
    instanceReader = mock(WorkflowInstanceReader.class);
    instanceWriter = mock(WorkflowInstanceWriter.class);
    llmExtractor = mock(LlmExtractor.class);
    eventBus = mock(DocumentEventBus.class);

    Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    engine =
        new WorkflowEngine(
            catalog, documentReader, instanceReader, instanceWriter, llmExtractor, eventBus, clock);

    documentId = UUID.randomUUID();
    storedDocumentId = UUID.randomUUID();

    WorkflowView view =
        new WorkflowView(
            ORG_ID,
            DOC_TYPE_ID,
            List.of(REVIEW_STAGE, MANAGER_STAGE, FILED_STAGE, REJECTED_STAGE),
            List.of(
                new TransitionView(STAGE_REVIEW, STAGE_MANAGER, "APPROVE", null),
                new TransitionView(STAGE_REVIEW, STAGE_REJECTED, "REJECT", null),
                new TransitionView(STAGE_MANAGER, STAGE_FILED, "APPROVE", null),
                new TransitionView(STAGE_MANAGER, STAGE_REVIEW, "FLAG", null)));
    when(catalog.getWorkflow(ORG_ID, DOC_TYPE_ID)).thenReturn(Optional.of(view));
  }

  @Test
  void approveFromReviewMovesToManagerApprovalAndPublishesOneEvent() {
    Document document = document(ReextractionStatus.NONE);
    WorkflowInstance reviewInstance = instance(STAGE_REVIEW, WorkflowStatus.AWAITING_REVIEW, null);
    WorkflowInstance managerInstance =
        instance(STAGE_MANAGER, WorkflowStatus.AWAITING_APPROVAL, null);
    when(documentReader.get(documentId)).thenReturn(Optional.of(document));
    when(instanceReader.getByDocumentId(documentId))
        .thenReturn(Optional.of(reviewInstance), Optional.of(managerInstance));

    WorkflowOutcome outcome = engine.applyAction(documentId, new WorkflowAction.Approve());

    assertThat(outcome).isInstanceOf(WorkflowOutcome.Success.class);
    WorkflowOutcome.Success success = (WorkflowOutcome.Success) outcome;
    assertThat(success.instance().currentStageId()).isEqualTo(STAGE_MANAGER);
    assertThat(success.instance().currentStatus()).isEqualTo(WorkflowStatus.AWAITING_APPROVAL);

    verify(instanceWriter, times(1))
        .advanceStage(documentId, STAGE_MANAGER, catalog, ORG_ID, DOC_TYPE_ID);

    DocumentStateChanged event = capturePublishedEvent();
    assertThat(event.documentId()).isEqualTo(documentId);
    assertThat(event.storedDocumentId()).isEqualTo(storedDocumentId);
    assertThat(event.organizationId()).isEqualTo(ORG_ID);
    assertThat(event.currentStage()).isEqualTo(STAGE_MANAGER);
    assertThat(event.currentStatus()).isEqualTo(WorkflowStatus.AWAITING_APPROVAL.name());
    assertThat(event.reextractionStatus()).isEqualTo(ReextractionStatus.NONE.name());
    assertThat(event.action()).isEqualTo("APPROVE");
    assertThat(event.comment()).isNull();
    assertThat(event.occurredAt()).isEqualTo(FIXED_NOW);
    verify(eventBus, times(1)).publish(any(DocumentEvent.class));
    verifyEventShape(event);
  }

  @Test
  void approveFromManagerApprovalMovesToFiledAndPublishesOneEvent() {
    Document document = document(ReextractionStatus.NONE);
    WorkflowInstance managerInstance =
        instance(STAGE_MANAGER, WorkflowStatus.AWAITING_APPROVAL, null);
    WorkflowInstance filedInstance = instance(STAGE_FILED, WorkflowStatus.FILED, null);
    when(documentReader.get(documentId)).thenReturn(Optional.of(document));
    when(instanceReader.getByDocumentId(documentId))
        .thenReturn(Optional.of(managerInstance), Optional.of(filedInstance));

    WorkflowOutcome outcome = engine.applyAction(documentId, new WorkflowAction.Approve());

    assertThat(outcome).isInstanceOf(WorkflowOutcome.Success.class);
    WorkflowOutcome.Success success = (WorkflowOutcome.Success) outcome;
    assertThat(success.instance().currentStageId()).isEqualTo(STAGE_FILED);
    assertThat(success.instance().currentStatus()).isEqualTo(WorkflowStatus.FILED);

    verify(instanceWriter, times(1))
        .advanceStage(documentId, STAGE_FILED, catalog, ORG_ID, DOC_TYPE_ID);

    DocumentStateChanged event = capturePublishedEvent();
    assertThat(event.currentStage()).isEqualTo(STAGE_FILED);
    assertThat(event.currentStatus()).isEqualTo(WorkflowStatus.FILED.name());
    assertThat(event.action()).isEqualTo("APPROVE");
    verify(eventBus, times(1)).publish(any(DocumentEvent.class));
    verifyEventShape(event);
  }

  @Test
  void rejectFromReviewMovesToRejectedAndPublishesOneEvent() {
    Document document = document(ReextractionStatus.NONE);
    WorkflowInstance reviewInstance = instance(STAGE_REVIEW, WorkflowStatus.AWAITING_REVIEW, null);
    WorkflowInstance rejectedInstance = instance(STAGE_REJECTED, WorkflowStatus.REJECTED, null);
    when(documentReader.get(documentId)).thenReturn(Optional.of(document));
    when(instanceReader.getByDocumentId(documentId))
        .thenReturn(Optional.of(reviewInstance), Optional.of(rejectedInstance));

    WorkflowOutcome outcome = engine.applyAction(documentId, new WorkflowAction.Reject());

    assertThat(outcome).isInstanceOf(WorkflowOutcome.Success.class);
    verify(instanceWriter, times(1))
        .advanceStage(documentId, STAGE_REJECTED, catalog, ORG_ID, DOC_TYPE_ID);

    DocumentStateChanged event = capturePublishedEvent();
    assertThat(event.currentStage()).isEqualTo(STAGE_REJECTED);
    assertThat(event.currentStatus()).isEqualTo(WorkflowStatus.REJECTED.name());
    assertThat(event.action()).isEqualTo("REJECT");
    verifyEventShape(event);
  }

  @Test
  void rejectFromManagerApprovalReturnsInvalidActionAndDoesNotWriteOrPublish() {
    Document document = document(ReextractionStatus.NONE);
    WorkflowInstance managerInstance =
        instance(STAGE_MANAGER, WorkflowStatus.AWAITING_APPROVAL, null);
    when(documentReader.get(documentId)).thenReturn(Optional.of(document));
    when(instanceReader.getByDocumentId(documentId)).thenReturn(Optional.of(managerInstance));

    WorkflowOutcome outcome = engine.applyAction(documentId, new WorkflowAction.Reject());

    assertThat(outcome).isInstanceOf(WorkflowOutcome.Failure.class);
    WorkflowOutcome.Failure failure = (WorkflowOutcome.Failure) outcome;
    assertThat(failure.error()).isInstanceOf(WorkflowError.InvalidAction.class);
    WorkflowError.InvalidAction invalid = (WorkflowError.InvalidAction) failure.error();
    assertThat(invalid.currentStageId()).isEqualTo(STAGE_MANAGER);
    assertThat(invalid.actionType()).isEqualTo("REJECT");

    verifyNoInteractions(instanceWriter);
    verifyNoInteractions(eventBus);
  }

  @Test
  void rejectFromFiledTerminalReturnsInvalidActionAndDoesNotWriteOrPublish() {
    Document document = document(ReextractionStatus.NONE);
    WorkflowInstance filedInstance = instance(STAGE_FILED, WorkflowStatus.FILED, null);
    when(documentReader.get(documentId)).thenReturn(Optional.of(document));
    when(instanceReader.getByDocumentId(documentId)).thenReturn(Optional.of(filedInstance));

    WorkflowOutcome outcome = engine.applyAction(documentId, new WorkflowAction.Reject());

    assertThat(outcome).isInstanceOf(WorkflowOutcome.Failure.class);
    assertThat(((WorkflowOutcome.Failure) outcome).error())
        .isInstanceOf(WorkflowError.InvalidAction.class);
    verifyNoInteractions(instanceWriter);
    verifyNoInteractions(eventBus);
  }

  @Test
  void flagWithEmptyCommentReturnsValidationFailedWithCommentPath() {
    WorkflowOutcome outcome = engine.applyAction(documentId, new WorkflowAction.Flag(""));

    assertThat(outcome).isInstanceOf(WorkflowOutcome.Failure.class);
    WorkflowError.ValidationFailed err =
        (WorkflowError.ValidationFailed) ((WorkflowOutcome.Failure) outcome).error();
    assertThat(err.details()).hasSize(1);
    assertThat(err.details().get(0).path()).isEqualTo("comment");

    verifyNoInteractions(documentReader);
    verifyNoInteractions(instanceReader);
    verifyNoInteractions(instanceWriter);
    verifyNoInteractions(eventBus);
  }

  @Test
  void flagWithWhitespaceCommentReturnsValidationFailedWithCommentPath() {
    WorkflowOutcome outcome = engine.applyAction(documentId, new WorkflowAction.Flag("   "));

    assertThat(outcome).isInstanceOf(WorkflowOutcome.Failure.class);
    WorkflowError.ValidationFailed err =
        (WorkflowError.ValidationFailed) ((WorkflowOutcome.Failure) outcome).error();
    assertThat(err.details()).hasSize(1);
    assertThat(err.details().get(0).path()).isEqualTo("comment");

    verifyNoInteractions(documentReader);
    verifyNoInteractions(instanceReader);
    verifyNoInteractions(instanceWriter);
    verifyNoInteractions(eventBus);
  }

  @Test
  void flagFromManagerApprovalMovesToReviewWithOriginAndPublishesEventWithComment() {
    Document document = document(ReextractionStatus.NONE);
    WorkflowInstance managerInstance =
        instance(STAGE_MANAGER, WorkflowStatus.AWAITING_APPROVAL, null);
    WorkflowInstance flaggedInstance =
        instance(STAGE_REVIEW, WorkflowStatus.FLAGGED, STAGE_MANAGER);
    when(documentReader.get(documentId)).thenReturn(Optional.of(document));
    when(instanceReader.getByDocumentId(documentId))
        .thenReturn(Optional.of(managerInstance), Optional.of(flaggedInstance));

    WorkflowOutcome outcome =
        engine.applyAction(documentId, new WorkflowAction.Flag("needs receipt"));

    assertThat(outcome).isInstanceOf(WorkflowOutcome.Success.class);
    WorkflowOutcome.Success success = (WorkflowOutcome.Success) outcome;
    assertThat(success.instance().currentStageId()).isEqualTo(STAGE_REVIEW);
    assertThat(success.instance().currentStatus()).isEqualTo(WorkflowStatus.FLAGGED);
    assertThat(success.instance().workflowOriginStage()).isEqualTo(STAGE_MANAGER);

    verify(instanceWriter, times(1))
        .setFlag(documentId, STAGE_MANAGER, "needs receipt", catalog, ORG_ID, DOC_TYPE_ID);

    DocumentStateChanged event = capturePublishedEvent();
    assertThat(event.currentStage()).isEqualTo(STAGE_REVIEW);
    assertThat(event.currentStatus()).isEqualTo(WorkflowStatus.FLAGGED.name());
    assertThat(event.action()).isEqualTo("FLAG");
    assertThat(event.comment()).isEqualTo("needs receipt");
    verify(eventBus, times(1)).publish(any(DocumentEvent.class));
    verifyEventShape(event);
  }

  @Test
  void resolveFromReviewWithNoTypeChangeReturnsToOriginAndClearsFlag() {
    Document document = document(ReextractionStatus.NONE);
    WorkflowInstance flaggedInstance =
        instance(STAGE_REVIEW, WorkflowStatus.FLAGGED, STAGE_MANAGER);
    WorkflowInstance restoredInstance =
        instance(STAGE_MANAGER, WorkflowStatus.AWAITING_APPROVAL, null);
    when(documentReader.get(documentId)).thenReturn(Optional.of(document));
    when(instanceReader.getByDocumentId(documentId))
        .thenReturn(Optional.of(flaggedInstance), Optional.of(restoredInstance));

    WorkflowOutcome outcome = engine.applyAction(documentId, new WorkflowAction.Resolve(null));

    assertThat(outcome).isInstanceOf(WorkflowOutcome.Success.class);
    WorkflowOutcome.Success success = (WorkflowOutcome.Success) outcome;
    assertThat(success.instance().currentStageId()).isEqualTo(STAGE_MANAGER);
    assertThat(success.instance().workflowOriginStage()).isNull();

    verify(instanceWriter, times(1)).clearFlag(documentId, catalog, ORG_ID, DOC_TYPE_ID);
    verify(llmExtractor, never()).extract(any(), any());

    DocumentStateChanged event = capturePublishedEvent();
    assertThat(event.currentStage()).isEqualTo(STAGE_MANAGER);
    assertThat(event.currentStatus()).isEqualTo(WorkflowStatus.AWAITING_APPROVAL.name());
    assertThat(event.action()).isEqualTo("RESOLVE");
    assertThat(event.comment()).isNull();
    assertThat(event.reextractionStatus()).isEqualTo(ReextractionStatus.NONE.name());
    verifyEventShape(event);
  }

  @Test
  void resolveWithTypeChangeWhileExtractionInProgressReturnsExtractionInProgress() {
    Document document = document(ReextractionStatus.IN_PROGRESS);
    WorkflowInstance flaggedInstance =
        instance(STAGE_REVIEW, WorkflowStatus.FLAGGED, STAGE_MANAGER);
    when(documentReader.get(documentId)).thenReturn(Optional.of(document));
    when(instanceReader.getByDocumentId(documentId)).thenReturn(Optional.of(flaggedInstance));

    WorkflowOutcome outcome =
        engine.applyAction(documentId, new WorkflowAction.Resolve(NEW_DOC_TYPE_ID));

    assertThat(outcome).isInstanceOf(WorkflowOutcome.Failure.class);
    WorkflowError.ExtractionInProgress err =
        (WorkflowError.ExtractionInProgress) ((WorkflowOutcome.Failure) outcome).error();
    assertThat(err.documentId()).isEqualTo(documentId);

    verify(llmExtractor, never()).extract(any(), any());
    verifyNoInteractions(instanceWriter);
    verifyNoInteractions(eventBus);
  }

  @Test
  void resolveWithTypeChangeDelegatesLifecycleToExtractorAndDoesNotDoubleMark() {
    Document document = document(ReextractionStatus.NONE);
    WorkflowInstance flaggedInstance =
        instance(STAGE_REVIEW, WorkflowStatus.FLAGGED, STAGE_MANAGER);
    when(documentReader.get(documentId)).thenReturn(Optional.of(document));
    when(instanceReader.getByDocumentId(documentId)).thenReturn(Optional.of(flaggedInstance));

    doAnswer(
            (InvocationOnMock inv) -> {
              Document seen = documentReader.get(documentId).orElseThrow();
              if (seen.reextractionStatus() == ReextractionStatus.IN_PROGRESS) {
                throw new RetypeAlreadyInProgressException(documentId);
              }
              return null;
            })
        .when(llmExtractor)
        .extract(eq(documentId), eq(NEW_DOC_TYPE_ID));

    org.mockito.InOrder order = org.mockito.Mockito.inOrder(eventBus, llmExtractor);

    WorkflowOutcome outcome =
        engine.applyAction(documentId, new WorkflowAction.Resolve(NEW_DOC_TYPE_ID));

    assertThat(outcome).isInstanceOf(WorkflowOutcome.Success.class);
    verify(instanceWriter, never()).clearFlag(any(), any(WorkflowCatalog.class), any(), any());
    verify(instanceWriter, never())
        .advanceStage(any(), any(), any(WorkflowCatalog.class), any(), any());

    DocumentStateChanged inProgressEvent = capturePublishedEvent();
    assertThat(inProgressEvent.documentId()).isEqualTo(documentId);
    assertThat(inProgressEvent.storedDocumentId()).isEqualTo(storedDocumentId);
    assertThat(inProgressEvent.organizationId()).isEqualTo(ORG_ID);
    assertThat(inProgressEvent.currentStage()).isEqualTo(STAGE_REVIEW);
    assertThat(inProgressEvent.currentStatus()).isEqualTo(WorkflowStatus.FLAGGED.name());
    assertThat(inProgressEvent.reextractionStatus())
        .isEqualTo(ReextractionStatus.IN_PROGRESS.name());
    assertThat(inProgressEvent.action()).isEqualTo("RESOLVE");
    assertThat(inProgressEvent.comment()).isNull();
    assertThat(inProgressEvent.occurredAt()).isEqualTo(FIXED_NOW);
    verify(eventBus, times(1)).publish(any(DocumentEvent.class));
    verifyEventShape(inProgressEvent);

    order.verify(eventBus).publish(any(DocumentEvent.class));
    order.verify(llmExtractor).extract(eq(documentId), eq(NEW_DOC_TYPE_ID));
  }

  private Document document(ReextractionStatus status) {
    return new Document(
        documentId,
        storedDocumentId,
        ORG_ID,
        DOC_TYPE_ID,
        Map.of("vendor", "Acme"),
        "raw text",
        Instant.parse("2026-04-27T10:00:00Z"),
        status);
  }

  private WorkflowInstance instance(
      String stageId, WorkflowStatus status, String workflowOriginStage) {
    return new WorkflowInstance(
        UUID.randomUUID(),
        documentId,
        ORG_ID,
        stageId,
        status,
        workflowOriginStage,
        workflowOriginStage == null ? null : "needs receipt",
        FIXED_NOW);
  }

  private DocumentStateChanged capturePublishedEvent() {
    ArgumentCaptor<DocumentEvent> captor = ArgumentCaptor.forClass(DocumentEvent.class);
    verify(eventBus).publish(captor.capture());
    DocumentEvent event = captor.getValue();
    assertThat(event).isInstanceOf(DocumentStateChanged.class);
    return (DocumentStateChanged) event;
  }

  private static void verifyEventShape(DocumentStateChanged event) {
    List<String> componentNames =
        java.util.Arrays.stream(event.getClass().getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .toList();
    assertThat(componentNames).doesNotContain("previousStage", "previousStatus");
    assertThat(componentNames)
        .containsExactlyInAnyOrder(
            "documentId",
            "storedDocumentId",
            "organizationId",
            "currentStage",
            "currentStatus",
            "reextractionStatus",
            "action",
            "comment",
            "occurredAt");
  }
}
