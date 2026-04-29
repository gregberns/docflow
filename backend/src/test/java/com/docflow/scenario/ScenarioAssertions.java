package com.docflow.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

public final class ScenarioAssertions {

  private ScenarioAssertions() {}

  public static DocumentAssert assertDocument(Map<String, Object> documentRow) {
    return new DocumentAssert(documentRow);
  }

  public static WorkflowInstanceAssert assertWorkflowInstance(
      Map<String, Object> workflowInstanceRow) {
    return new WorkflowInstanceAssert(workflowInstanceRow);
  }

  public static EventsAssert assertEvents(List<Object> recordedEvents) {
    return new EventsAssert(recordedEvents);
  }

  public static final class DocumentAssert {
    private final Map<String, Object> row;

    DocumentAssert(Map<String, Object> row) {
      this.row = row;
    }

    public DocumentAssert hasDetectedDocumentType(String docType) {
      assertThat(row.get("detected_document_type")).isEqualTo(docType);
      return this;
    }

    public DocumentAssert hasReextractionStatus(String status) {
      assertThat(row.get("reextraction_status")).isEqualTo(status);
      return this;
    }

    public DocumentAssert hasExtractedFieldsContaining(
        String fieldsJson, String key, String value) {
      assertThat(fieldsJson).contains("\"" + key + "\"").contains("\"" + value + "\"");
      return this;
    }
  }

  public static final class WorkflowInstanceAssert {
    private final Map<String, Object> row;

    WorkflowInstanceAssert(Map<String, Object> row) {
      this.row = row;
    }

    public WorkflowInstanceAssert hasCurrentStageId(String stageId) {
      assertThat(row.get("current_stage_id")).isEqualTo(stageId);
      return this;
    }

    public WorkflowInstanceAssert hasCurrentStatus(String status) {
      assertThat(row.get("current_status")).isEqualTo(status);
      return this;
    }

    public WorkflowInstanceAssert hasWorkflowOriginStage(String stage) {
      if (stage == null) {
        assertThat(row.get("workflow_origin_stage")).isNull();
      } else {
        assertThat(row.get("workflow_origin_stage")).isEqualTo(stage);
      }
      return this;
    }

    public WorkflowInstanceAssert hasFlagComment(String comment) {
      if (comment == null) {
        assertThat(row.get("flag_comment")).isNull();
      } else {
        assertThat(row.get("flag_comment")).isEqualTo(comment);
      }
      return this;
    }
  }

  public static final class EventsAssert {
    private final List<Object> events;

    EventsAssert(List<Object> events) {
      this.events = events;
    }

    public EventsAssert containsInOrder(List<ScenarioFixture.EventExpectation> expected) {
      int cursor = 0;
      for (ScenarioFixture.EventExpectation expectation : expected) {
        boolean matched = false;
        for (int i = cursor; i < events.size(); i++) {
          if (matches(events.get(i), expectation)) {
            cursor = i + 1;
            matched = true;
            break;
          }
        }
        if (!matched) {
          throw new AssertionError(
              "expected event not found in recorded sequence (order-preserving): " + expectation);
        }
      }
      return this;
    }

    private static boolean matches(Object event, ScenarioFixture.EventExpectation expectation) {
      String simple = event.getClass().getSimpleName();
      if (expectation.type() != null && !expectation.type().equals(simple)) {
        return false;
      }
      if (expectation.currentStep() != null
          && event instanceof com.docflow.c3.events.ProcessingStepChanged psc
          && !expectation.currentStep().equals(psc.currentStep())) {
        return false;
      }
      if (event instanceof com.docflow.workflow.events.DocumentStateChanged dsc) {
        if (expectation.currentStage() != null
            && !expectation.currentStage().equals(dsc.currentStage())) {
          return false;
        }
        if (expectation.currentStatus() != null
            && !expectation.currentStatus().equals(dsc.currentStatus())) {
          return false;
        }
        if (expectation.reextractionStatus() != null
            && !expectation.reextractionStatus().equals(dsc.reextractionStatus())) {
          return false;
        }
        if (expectation.action() != null && !expectation.action().equals(dsc.action())) {
          return false;
        }
      }
      return true;
    }
  }
}
