package com.docflow.scenario;

import java.util.List;
import java.util.Map;

public record ScenarioFixture(
    String scenarioId,
    String description,
    String organizationId,
    String inputPdf,
    List<Input> inputs,
    Classification classification,
    Extraction extraction,
    List<Action> actions,
    ExpectedEndState expectedEndState) {

  public record Input(
      String inputPdf,
      String organizationId,
      Classification classification,
      Extraction extraction) {}

  public record Classification(String docType, String error) {}

  public record Extraction(Map<String, Object> fields, String error) {}

  public record Action(String type, String comment, String newDocTypeId, Integer expectedStatus) {}

  public record ExpectedEndState(
      DocumentExpectations document,
      WorkflowInstanceExpectations workflowInstance,
      List<EventExpectation> events) {}

  public record DocumentExpectations(
      String detectedDocumentType,
      String reextractionStatus,
      Map<String, Object> extractedFieldsContains) {}

  public record WorkflowInstanceExpectations(
      String currentStageId,
      String currentStatus,
      String workflowOriginStage,
      String flagComment) {}

  public record EventExpectation(
      String type,
      String currentStep,
      String currentStage,
      String currentStatus,
      String reextractionStatus,
      String action) {}
}
