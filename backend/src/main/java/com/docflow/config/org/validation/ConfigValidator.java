package com.docflow.config.org.validation;

import com.docflow.config.org.ArrayItemSchema;
import com.docflow.config.org.DocTypeDefinition;
import com.docflow.config.org.FieldDefinition;
import com.docflow.config.org.FieldType;
import com.docflow.config.org.OrgConfig;
import com.docflow.config.org.OrganizationDefinition;
import com.docflow.config.org.StageDefinition;
import com.docflow.config.org.StageKind;
import com.docflow.config.org.TransitionDefinition;
import com.docflow.config.org.WorkflowDefinition;
import com.docflow.config.org.WorkflowStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class ConfigValidator {

  private static final String REVIEW_STAGE_ID = "Review";

  public void validate(OrgConfig config) {
    Objects.requireNonNull(config, "config");
    List<String> errors = new ArrayList<>();

    Map<String, DocTypeDefinition> docTypeIndex = indexDocTypes(config.docTypes());

    checkOrganizationDocTypeRefs(config, docTypeIndex, errors);
    checkWorkflows(config, errors);
    checkFieldMetadata(config, errors);

    if (!errors.isEmpty()) {
      throw new ConfigValidationException(errors);
    }
  }

  private static Map<String, DocTypeDefinition> indexDocTypes(List<DocTypeDefinition> docTypes) {
    Map<String, DocTypeDefinition> index = new HashMap<>();
    if (docTypes == null) {
      return index;
    }
    for (DocTypeDefinition dt : docTypes) {
      if (dt == null || dt.organizationId() == null || dt.id() == null) {
        continue;
      }
      index.put(docTypeKey(dt.organizationId(), dt.id()), dt);
    }
    return index;
  }

  private static String docTypeKey(String orgId, String docTypeId) {
    return orgId + "::" + docTypeId;
  }

  private static void checkOrganizationDocTypeRefs(
      OrgConfig config, Map<String, DocTypeDefinition> docTypeIndex, List<String> errors) {
    if (config.organizations() == null) {
      return;
    }
    for (OrganizationDefinition org : config.organizations()) {
      if (org == null || org.documentTypeIds() == null) {
        continue;
      }
      for (String docTypeId : org.documentTypeIds()) {
        if (docTypeId == null) {
          continue;
        }
        if (!docTypeIndex.containsKey(docTypeKey(org.id(), docTypeId))) {
          errors.add(
              "CV-1: organization '"
                  + org.id()
                  + "' references unknown documentTypeId '"
                  + docTypeId
                  + "'");
        }
      }
    }
  }

  private static void checkWorkflows(OrgConfig config, List<String> errors) {
    if (config.workflows() == null) {
      return;
    }
    Set<String> seenWorkflowKeys = new HashSet<>();
    for (WorkflowDefinition workflow : config.workflows()) {
      if (workflow == null) {
        continue;
      }
      String workflowLabel = workflowLabel(workflow);

      checkWorkflowStageReferences(workflow, workflowLabel, errors);
      checkWorkflowHasTerminal(workflow, workflowLabel, errors);
      checkWorkflowFirstStageIsReview(workflow, workflowLabel, errors);
      checkWorkflowStageKindStatusCompatibility(workflow, workflowLabel, errors);
      checkWorkflowUniqueness(workflow, workflowLabel, seenWorkflowKeys, errors);
    }
  }

  private static String workflowLabel(WorkflowDefinition workflow) {
    return "(" + workflow.organizationId() + ", " + workflow.documentTypeId() + ")";
  }

  private static void checkWorkflowStageReferences(
      WorkflowDefinition workflow, String workflowLabel, List<String> errors) {
    if (workflow.transitions() == null) {
      return;
    }
    Set<String> stageIds = new HashSet<>();
    if (workflow.stages() != null) {
      for (StageDefinition stage : workflow.stages()) {
        if (stage != null && stage.id() != null) {
          stageIds.add(stage.id());
        }
      }
    }
    for (TransitionDefinition transition : workflow.transitions()) {
      if (transition == null) {
        continue;
      }
      if (transition.from() != null && !stageIds.contains(transition.from())) {
        errors.add(
            "CV-2: workflow "
                + workflowLabel
                + " transition references unknown 'from' stage '"
                + transition.from()
                + "'");
      }
      if (transition.to() != null && !stageIds.contains(transition.to())) {
        errors.add(
            "CV-2: workflow "
                + workflowLabel
                + " transition references unknown 'to' stage '"
                + transition.to()
                + "'");
      }
    }
  }

  private static void checkWorkflowHasTerminal(
      WorkflowDefinition workflow, String workflowLabel, List<String> errors) {
    if (workflow.stages() == null) {
      return;
    }
    boolean hasTerminal = false;
    for (StageDefinition stage : workflow.stages()) {
      if (stage != null && stage.kind() == StageKind.TERMINAL) {
        hasTerminal = true;
        break;
      }
    }
    if (!hasTerminal) {
      errors.add("CV-3: workflow " + workflowLabel + " has no TERMINAL stage");
    }
  }

  private static void checkWorkflowFirstStageIsReview(
      WorkflowDefinition workflow, String workflowLabel, List<String> errors) {
    if (workflow.stages() == null || workflow.stages().isEmpty()) {
      return;
    }
    StageDefinition first = workflow.stages().get(0);
    if (first == null) {
      return;
    }
    if (!REVIEW_STAGE_ID.equals(first.id()) || first.kind() != StageKind.REVIEW) {
      errors.add(
          "CV-5: workflow "
              + workflowLabel
              + " first stage must be id='"
              + REVIEW_STAGE_ID
              + "' kind=REVIEW (got id='"
              + first.id()
              + "' kind="
              + first.kind()
              + ")");
    }
  }

  private static void checkWorkflowStageKindStatusCompatibility(
      WorkflowDefinition workflow, String workflowLabel, List<String> errors) {
    if (workflow.stages() == null) {
      return;
    }
    for (StageDefinition stage : workflow.stages()) {
      if (stage == null || stage.kind() == null || stage.canonicalStatus() == null) {
        continue;
      }
      WorkflowStatus status = stage.canonicalStatus();
      if (status == WorkflowStatus.FLAGGED) {
        errors.add(
            "CV-6: workflow "
                + workflowLabel
                + " stage '"
                + stage.id()
                + "' declares canonicalStatus=FLAGGED which is runtime-only");
        continue;
      }
      boolean compatible =
          switch (stage.kind()) {
            case REVIEW -> status == WorkflowStatus.AWAITING_REVIEW;
            case APPROVAL -> status == WorkflowStatus.AWAITING_APPROVAL;
            case TERMINAL -> status == WorkflowStatus.FILED || status == WorkflowStatus.REJECTED;
          };
      if (!compatible) {
        errors.add(
            "CV-6: workflow "
                + workflowLabel
                + " stage '"
                + stage.id()
                + "' kind="
                + stage.kind()
                + " incompatible with canonicalStatus="
                + status);
      }
    }
  }

  private static void checkWorkflowUniqueness(
      WorkflowDefinition workflow,
      String workflowLabel,
      Set<String> seenWorkflowKeys,
      List<String> errors) {
    if (workflow.organizationId() == null || workflow.documentTypeId() == null) {
      return;
    }
    String key = docTypeKey(workflow.organizationId(), workflow.documentTypeId());
    if (!seenWorkflowKeys.add(key)) {
      errors.add("CV-7: duplicate workflow for " + workflowLabel);
    }
  }

  private static void checkFieldMetadata(OrgConfig config, List<String> errors) {
    if (config.docTypes() == null) {
      return;
    }
    for (DocTypeDefinition docType : config.docTypes()) {
      if (docType == null || docType.fields() == null) {
        continue;
      }
      String docTypeLabel = "(" + docType.organizationId() + ", " + docType.id() + ")";
      for (FieldDefinition field : docType.fields()) {
        checkField(docTypeLabel, "", field, errors);
      }
    }
  }

  private static void checkField(
      String docTypeLabel, String pathPrefix, FieldDefinition field, List<String> errors) {
    if (field == null || field.type() == null || field.name() == null) {
      return;
    }
    String fieldPath = pathPrefix + field.name();

    checkEnumDuplicates(docTypeLabel, fieldPath, field, errors);
    checkFieldTypeMetadata(docTypeLabel, fieldPath, field, errors);

    if (field.type() == FieldType.ARRAY && field.itemSchema() != null) {
      ArrayItemSchema schema = field.itemSchema();
      if (schema.fields() != null) {
        for (FieldDefinition nested : schema.fields()) {
          checkField(docTypeLabel, fieldPath + ".", nested, errors);
        }
      }
    }
  }

  private static void checkEnumDuplicates(
      String docTypeLabel, String fieldPath, FieldDefinition field, List<String> errors) {
    if (field.enumValues() == null) {
      return;
    }
    Set<String> seen = new HashSet<>();
    Set<String> dupes = new HashSet<>();
    for (String value : field.enumValues()) {
      if (!seen.add(value)) {
        dupes.add(value);
      }
    }
    if (!dupes.isEmpty()) {
      List<String> sorted = new ArrayList<>(dupes);
      sorted.sort(String::compareTo);
      errors.add(
          "CV-4: doc-type "
              + docTypeLabel
              + " field '"
              + fieldPath
              + "' has duplicate enumValues "
              + sorted);
    }
  }

  private static void checkFieldTypeMetadata(
      String docTypeLabel, String fieldPath, FieldDefinition field, List<String> errors) {
    boolean enumValuesPresent = field.enumValues() != null && !field.enumValues().isEmpty();
    boolean itemSchemaPresent = field.itemSchema() != null;

    switch (field.type()) {
      case ENUM -> {
        if (!enumValuesPresent) {
          errors.add(
              "CV-8: doc-type "
                  + docTypeLabel
                  + " field '"
                  + fieldPath
                  + "' type=ENUM requires non-empty enumValues");
        }
        if (itemSchemaPresent) {
          errors.add(
              "CV-8: doc-type "
                  + docTypeLabel
                  + " field '"
                  + fieldPath
                  + "' type=ENUM must not declare itemSchema");
        }
      }
      case ARRAY -> {
        if (!itemSchemaPresent) {
          errors.add(
              "CV-8: doc-type "
                  + docTypeLabel
                  + " field '"
                  + fieldPath
                  + "' type=ARRAY requires non-null itemSchema");
        }
        if (enumValuesPresent) {
          errors.add(
              "CV-8: doc-type "
                  + docTypeLabel
                  + " field '"
                  + fieldPath
                  + "' type=ARRAY must not declare enumValues");
        }
      }
      case STRING, DATE, DECIMAL -> {
        if (enumValuesPresent) {
          errors.add(
              "CV-8: doc-type "
                  + docTypeLabel
                  + " field '"
                  + fieldPath
                  + "' type="
                  + field.type()
                  + " must not declare enumValues");
        }
        if (itemSchemaPresent) {
          errors.add(
              "CV-8: doc-type "
                  + docTypeLabel
                  + " field '"
                  + fieldPath
                  + "' type="
                  + field.type()
                  + " must not declare itemSchema");
        }
      }
      default -> {
        // unreachable: FieldType is exhaustively covered above
      }
    }
  }
}
