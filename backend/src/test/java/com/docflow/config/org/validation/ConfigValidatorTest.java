package com.docflow.config.org.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docflow.config.org.DocTypeDefinition;
import com.docflow.config.org.FieldDefinition;
import com.docflow.config.org.FieldType;
import com.docflow.config.org.InputModality;
import com.docflow.config.org.OrgConfig;
import com.docflow.config.org.OrganizationDefinition;
import com.docflow.config.org.StageDefinition;
import com.docflow.config.org.StageKind;
import com.docflow.config.org.TransitionAction;
import com.docflow.config.org.TransitionDefinition;
import com.docflow.config.org.WorkflowDefinition;
import com.docflow.config.org.WorkflowStatus;
import com.docflow.config.org.loader.ConfigLoader;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigValidatorTest {

  private static final String FIXTURE_ROOT = "classpath:loader-fixtures/seed/";

  private final ConfigValidator validator = new ConfigValidator();

  @Test
  void seedFixtureLoadsAndValidatesCleanly() {
    OrgConfig config = new ConfigLoader().load(FIXTURE_ROOT);
    assertThatCode(() -> validator.validate(config)).doesNotThrowAnyException();
  }

  @Test
  void cv1UnknownDocTypeRefIsRejected() {
    OrgConfig config = baseConfig();
    OrganizationDefinition org = config.organizations().get(0);
    OrganizationDefinition tampered =
        new OrganizationDefinition(
            org.id(), org.displayName(), org.iconId(), List.of("does-not-exist"));

    OrgConfig bad = new OrgConfig(List.of(tampered), config.docTypes(), config.workflows());

    assertThatThrownBy(() -> validator.validate(bad))
        .isInstanceOf(ConfigValidationException.class)
        .satisfies(ex -> assertOnlyTrigger(ex, "CV-1"))
        .hasMessageContaining("does-not-exist");
  }

  @Test
  void cv2UnknownStageReferenceIsRejected() {
    OrgConfig config = baseConfig();
    WorkflowDefinition workflow = config.workflows().get(0);

    List<TransitionDefinition> tampered = new ArrayList<>(workflow.transitions());
    tampered.add(new TransitionDefinition("Review", TransitionAction.APPROVE, "Ghost-Stage", null));

    WorkflowDefinition bad =
        new WorkflowDefinition(
            workflow.organizationId(), workflow.documentTypeId(), workflow.stages(), tampered);

    OrgConfig badConfig = new OrgConfig(config.organizations(), config.docTypes(), List.of(bad));

    assertThatThrownBy(() -> validator.validate(badConfig))
        .isInstanceOf(ConfigValidationException.class)
        .satisfies(ex -> assertOnlyTrigger(ex, "CV-2"))
        .hasMessageContaining("Ghost-Stage");
  }

  @Test
  void cv3MissingTerminalStageIsRejected() {
    OrgConfig config = baseConfig();
    WorkflowDefinition workflow = config.workflows().get(0);

    List<StageDefinition> nonTerminal =
        workflow.stages().stream().filter(s -> s.kind() != StageKind.TERMINAL).toList();

    List<TransitionDefinition> trimmedTransitions =
        workflow.transitions().stream()
            .filter(t -> stageIds(nonTerminal).contains(t.from()))
            .filter(t -> stageIds(nonTerminal).contains(t.to()))
            .toList();

    WorkflowDefinition bad =
        new WorkflowDefinition(
            workflow.organizationId(),
            workflow.documentTypeId(),
            nonTerminal,
            trimmedTransitions.isEmpty()
                ? List.of(
                    new TransitionDefinition(
                        "Review", TransitionAction.AUTO_ADVANCE, "Review", null))
                : trimmedTransitions);

    OrgConfig badConfig = new OrgConfig(config.organizations(), config.docTypes(), List.of(bad));

    assertThatThrownBy(() -> validator.validate(badConfig))
        .isInstanceOf(ConfigValidationException.class)
        .satisfies(ex -> assertOnlyTrigger(ex, "CV-3"))
        .hasMessageContaining("no TERMINAL stage");
  }

  @Test
  void cv4DuplicateEnumValuesIsRejected() {
    OrgConfig base = baseConfig();
    DocTypeDefinition docType =
        new DocTypeDefinition(
            "test-org",
            "widget",
            "Widget",
            InputModality.TEXT,
            List.of(
                new FieldDefinition(
                    "color", FieldType.ENUM, true, List.of("red", "blue", "red"), null)));
    OrganizationDefinition org =
        new OrganizationDefinition("test-org", "Test Org", "icon-test", List.of("widget"));
    WorkflowDefinition workflow = trivialWorkflow("test-org", "widget");

    OrgConfig bad =
        new OrgConfig(
            mergeOrganizations(base.organizations(), org),
            mergeDocTypes(base.docTypes(), docType),
            mergeWorkflows(base.workflows(), workflow));

    assertThatThrownBy(() -> validator.validate(bad))
        .isInstanceOf(ConfigValidationException.class)
        .satisfies(ex -> assertOnlyTrigger(ex, "CV-4"))
        .hasMessageContaining("color")
        .hasMessageContaining("red");
  }

  @Test
  void cv5FirstStageNotReviewIsRejected() {
    OrgConfig config = baseConfig();
    WorkflowDefinition workflow = config.workflows().get(0);

    List<StageDefinition> reordered = new ArrayList<>(workflow.stages());
    StageDefinition first = reordered.remove(0);
    reordered.add(first);

    WorkflowDefinition bad =
        new WorkflowDefinition(
            workflow.organizationId(),
            workflow.documentTypeId(),
            reordered,
            workflow.transitions());

    OrgConfig badConfig = new OrgConfig(config.organizations(), config.docTypes(), List.of(bad));

    assertThatThrownBy(() -> validator.validate(badConfig))
        .isInstanceOf(ConfigValidationException.class)
        .satisfies(ex -> assertOnlyTrigger(ex, "CV-5"))
        .hasMessageContaining("first stage");
  }

  @Test
  void cv6IncompatibleKindStatusIsRejected() {
    OrgConfig config = baseConfig();
    WorkflowDefinition workflow = config.workflows().get(0);

    List<StageDefinition> tampered = new ArrayList<>(workflow.stages());
    StageDefinition review = tampered.get(0);
    tampered.set(
        0,
        new StageDefinition(
            review.id(), review.displayName(), review.kind(), WorkflowStatus.FILED, review.role()));

    WorkflowDefinition bad =
        new WorkflowDefinition(
            workflow.organizationId(), workflow.documentTypeId(), tampered, workflow.transitions());

    OrgConfig badConfig = new OrgConfig(config.organizations(), config.docTypes(), List.of(bad));

    assertThatThrownBy(() -> validator.validate(badConfig))
        .isInstanceOf(ConfigValidationException.class)
        .satisfies(ex -> assertOnlyTrigger(ex, "CV-6"))
        .hasMessageContaining("incompatible");
  }

  @Test
  void cv6FlaggedCanonicalStatusOnStageIsRejected() {
    OrgConfig config = baseConfig();
    WorkflowDefinition workflow = config.workflows().get(0);

    List<StageDefinition> tampered = new ArrayList<>(workflow.stages());
    StageDefinition review = tampered.get(0);
    tampered.set(
        0,
        new StageDefinition(
            review.id(),
            review.displayName(),
            review.kind(),
            WorkflowStatus.FLAGGED,
            review.role()));

    WorkflowDefinition bad =
        new WorkflowDefinition(
            workflow.organizationId(), workflow.documentTypeId(), tampered, workflow.transitions());

    OrgConfig badConfig = new OrgConfig(config.organizations(), config.docTypes(), List.of(bad));

    assertThatThrownBy(() -> validator.validate(badConfig))
        .isInstanceOf(ConfigValidationException.class)
        .satisfies(ex -> assertOnlyTrigger(ex, "CV-6"))
        .hasMessageContaining("FLAGGED");
  }

  @Test
  void cv7DuplicateWorkflowKeyIsRejected() {
    OrgConfig config = baseConfig();
    WorkflowDefinition workflow = config.workflows().get(0);

    List<WorkflowDefinition> doubled = new ArrayList<>(config.workflows());
    doubled.add(workflow);

    OrgConfig bad = new OrgConfig(config.organizations(), config.docTypes(), doubled);

    assertThatThrownBy(() -> validator.validate(bad))
        .isInstanceOf(ConfigValidationException.class)
        .satisfies(ex -> assertOnlyTrigger(ex, "CV-7"))
        .hasMessageContaining("duplicate workflow");
  }

  @Test
  void cv8EnumWithoutEnumValuesIsRejected() {
    OrgConfig base = baseConfig();
    DocTypeDefinition docType =
        new DocTypeDefinition(
            "test-org",
            "widget",
            "Widget",
            InputModality.TEXT,
            List.of(new FieldDefinition("color", FieldType.ENUM, true, List.of(), null)));
    OrganizationDefinition org =
        new OrganizationDefinition("test-org", "Test Org", "icon-test", List.of("widget"));
    WorkflowDefinition workflow = trivialWorkflow("test-org", "widget");

    OrgConfig bad =
        new OrgConfig(
            mergeOrganizations(base.organizations(), org),
            mergeDocTypes(base.docTypes(), docType),
            mergeWorkflows(base.workflows(), workflow));

    assertThatThrownBy(() -> validator.validate(bad))
        .isInstanceOf(ConfigValidationException.class)
        .satisfies(ex -> assertOnlyTrigger(ex, "CV-8"))
        .hasMessageContaining("ENUM requires non-empty enumValues");
  }

  @Test
  void cv8ArrayWithoutItemSchemaIsRejected() {
    OrgConfig base = baseConfig();
    DocTypeDefinition docType =
        new DocTypeDefinition(
            "test-org",
            "widget",
            "Widget",
            InputModality.TEXT,
            List.of(new FieldDefinition("items", FieldType.ARRAY, true, null, null)));
    OrganizationDefinition org =
        new OrganizationDefinition("test-org", "Test Org", "icon-test", List.of("widget"));
    WorkflowDefinition workflow = trivialWorkflow("test-org", "widget");

    OrgConfig bad =
        new OrgConfig(
            mergeOrganizations(base.organizations(), org),
            mergeDocTypes(base.docTypes(), docType),
            mergeWorkflows(base.workflows(), workflow));

    assertThatThrownBy(() -> validator.validate(bad))
        .isInstanceOf(ConfigValidationException.class)
        .satisfies(ex -> assertOnlyTrigger(ex, "CV-8"))
        .hasMessageContaining("ARRAY requires non-null itemSchema");
  }

  @Test
  void cv8StringWithEnumValuesIsRejected() {
    OrgConfig base = baseConfig();
    DocTypeDefinition docType =
        new DocTypeDefinition(
            "test-org",
            "widget",
            "Widget",
            InputModality.TEXT,
            List.of(new FieldDefinition("name", FieldType.STRING, true, List.of("a", "b"), null)));
    OrganizationDefinition org =
        new OrganizationDefinition("test-org", "Test Org", "icon-test", List.of("widget"));
    WorkflowDefinition workflow = trivialWorkflow("test-org", "widget");

    OrgConfig bad =
        new OrgConfig(
            mergeOrganizations(base.organizations(), org),
            mergeDocTypes(base.docTypes(), docType),
            mergeWorkflows(base.workflows(), workflow));

    assertThatThrownBy(() -> validator.validate(bad))
        .isInstanceOf(ConfigValidationException.class)
        .satisfies(ex -> assertOnlyTrigger(ex, "CV-8"))
        .hasMessageContaining("must not declare enumValues");
  }

  @Test
  void multipleViolationsAreAccumulatedInSingleException() {
    OrgConfig base = baseConfig();
    OrganizationDefinition orgWithBadRef =
        new OrganizationDefinition("test-org", "Test Org", "icon-test", List.of("does-not-exist"));
    DocTypeDefinition badDocType =
        new DocTypeDefinition(
            "test-org-2",
            "widget",
            "Widget",
            InputModality.TEXT,
            List.of(
                new FieldDefinition("color", FieldType.ENUM, true, List.of("red", "red"), null)));
    OrganizationDefinition orgForDocType =
        new OrganizationDefinition("test-org-2", "Test Org 2", "icon-test", List.of("widget"));
    WorkflowDefinition workflow = trivialWorkflow("test-org-2", "widget");

    OrgConfig bad =
        new OrgConfig(
            mergeOrganizations(
                mergeOrganizations(base.organizations(), orgWithBadRef), orgForDocType),
            mergeDocTypes(base.docTypes(), badDocType),
            mergeWorkflows(base.workflows(), workflow));

    assertThatThrownBy(() -> validator.validate(bad))
        .isInstanceOf(ConfigValidationException.class)
        .satisfies(
            ex -> {
              ConfigValidationException cve = (ConfigValidationException) ex;
              assertThat(cve.errors()).hasSizeGreaterThanOrEqualTo(2);
              assertThat(cve.getMessage()).contains("CV-1").contains("does-not-exist");
              assertThat(cve.getMessage()).contains("CV-4").contains("red");
            });
  }

  @Test
  void emptyConfigValidatesCleanly() {
    OrgConfig empty = new OrgConfig(List.of(), List.of(), List.of());
    assertThatCode(() -> validator.validate(empty)).doesNotThrowAnyException();
  }

  private static void assertOnlyTrigger(Throwable ex, String code) {
    ConfigValidationException cve = (ConfigValidationException) ex;
    assertThat(cve.errors()).as("errors for %s", code).isNotEmpty();
    assertThat(cve.errors())
        .as("every error must reference %s and only %s", code, code)
        .allSatisfy(err -> assertThat(err).startsWith(code + ":"));
  }

  private static List<String> stageIds(List<StageDefinition> stages) {
    return stages.stream().map(StageDefinition::id).toList();
  }

  private static List<OrganizationDefinition> mergeOrganizations(
      List<OrganizationDefinition> existing, OrganizationDefinition added) {
    List<OrganizationDefinition> merged = new ArrayList<>(existing);
    merged.add(added);
    return merged;
  }

  private static List<DocTypeDefinition> mergeDocTypes(
      List<DocTypeDefinition> existing, DocTypeDefinition added) {
    List<DocTypeDefinition> merged = new ArrayList<>(existing);
    merged.add(added);
    return merged;
  }

  private static List<WorkflowDefinition> mergeWorkflows(
      List<WorkflowDefinition> existing, WorkflowDefinition added) {
    List<WorkflowDefinition> merged = new ArrayList<>(existing);
    merged.add(added);
    return merged;
  }

  private static WorkflowDefinition trivialWorkflow(String organizationId, String documentTypeId) {
    return new WorkflowDefinition(
        organizationId,
        documentTypeId,
        List.of(
            new StageDefinition(
                "Review", "Review", StageKind.REVIEW, WorkflowStatus.AWAITING_REVIEW, null),
            new StageDefinition("Filed", "Filed", StageKind.TERMINAL, WorkflowStatus.FILED, null),
            new StageDefinition(
                "Rejected", "Rejected", StageKind.TERMINAL, WorkflowStatus.REJECTED, null)),
        List.of(
            new TransitionDefinition("Review", TransitionAction.APPROVE, "Filed", null),
            new TransitionDefinition("Review", TransitionAction.REJECT, "Rejected", null)));
  }

  private static OrgConfig baseConfig() {
    return new ConfigLoader().load(FIXTURE_ROOT);
  }
}
