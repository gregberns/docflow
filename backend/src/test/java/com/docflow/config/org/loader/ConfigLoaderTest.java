package com.docflow.config.org.loader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docflow.config.org.ArrayItemSchema;
import com.docflow.config.org.DocTypeDefinition;
import com.docflow.config.org.FieldDefinition;
import com.docflow.config.org.FieldType;
import com.docflow.config.org.GuardOp;
import com.docflow.config.org.OrgConfig;
import com.docflow.config.org.TransitionAction;
import com.docflow.config.org.TransitionDefinition;
import com.docflow.config.org.WorkflowDefinition;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;

class ConfigLoaderTest {

  private static final String FIXTURE_ROOT = "classpath:loader-fixtures/seed/";
  private static final String INVALID_YAML_ROOT = "classpath:loader-fixtures/invalid-yaml/seed/";
  private static final String MISSING_REQUIRED_ROOT =
      "classpath:loader-fixtures/missing-required/seed/";

  private final ConfigLoader loader = new ConfigLoader();

  @Test
  void loadsThreeOrganizationsNineDocTypesAndNineWorkflows() {
    OrgConfig config = loader.load(FIXTURE_ROOT);

    assertThat(config.organizations()).hasSize(3);
    assertThat(config.docTypes()).hasSize(9);
    assertThat(config.workflows()).hasSize(9);

    assertThat(config.organizations())
        .extracting(o -> o.documentTypeIds().size())
        .containsExactly(3, 3, 3);
  }

  @Test
  void invoiceDocTypeContainsArrayFieldWithItemSchema() {
    OrgConfig config = loader.load(FIXTURE_ROOT);

    DocTypeDefinition invoice =
        config.docTypes().stream()
            .filter(dt -> "test-bistro".equals(dt.organizationId()) && "invoice".equals(dt.id()))
            .findFirst()
            .orElseThrow();

    FieldDefinition lineItems =
        invoice.fields().stream()
            .filter(f -> "line-items".equals(f.name()))
            .findFirst()
            .orElseThrow();

    assertThat(lineItems.type()).isEqualTo(FieldType.ARRAY);
    ArrayItemSchema itemSchema = lineItems.itemSchema();
    assertThat(itemSchema).isNotNull();
    assertThat(itemSchema.fields()).hasSize(4);
    assertThat(itemSchema.fields())
        .extracting(FieldDefinition::name)
        .containsExactly("description", "quantity", "unitPrice", "total");
  }

  @Test
  void waiverWorkflowDeclaresInverseGuardTransitionPair() {
    OrgConfig config = loader.load(FIXTURE_ROOT);

    WorkflowDefinition waiver =
        config.workflows().stream()
            .filter(
                w ->
                    "test-construction".equals(w.organizationId())
                        && "lien-waiver".equals(w.documentTypeId()))
            .findFirst()
            .orElseThrow();

    List<TransitionDefinition> reviewApproveTransitions =
        waiver.transitions().stream()
            .filter(t -> "Review".equals(t.from()) && t.action() == TransitionAction.APPROVE)
            .toList();

    assertThat(reviewApproveTransitions).hasSize(2);
    assertThat(reviewApproveTransitions)
        .allSatisfy(t -> assertThat(t.guard()).isNotNull())
        .allSatisfy(t -> assertThat(t.guard().field()).isEqualTo("waiverType"))
        .allSatisfy(t -> assertThat(t.guard().value()).isEqualTo("unconditional"));

    assertThat(reviewApproveTransitions)
        .extracting(t -> t.guard().op())
        .containsExactlyInAnyOrder(GuardOp.EQ, GuardOp.NEQ);
  }

  @Test
  void invalidYamlIsWrappedInConfigLoadExceptionWithFileAndLine() {
    assertThatThrownBy(() -> loader.load(INVALID_YAML_ROOT))
        .isInstanceOf(ConfigLoadException.class)
        .satisfies(
            ex -> {
              ConfigLoadException cle = (ConfigLoadException) ex;
              assertThat(cle.sourcePath()).contains("organizations.yaml");
              assertThat(cle.lineNumber()).isPositive();
              assertThat(cle.getCause()).isInstanceOf(JacksonException.class);
            });
  }

  @Test
  void validationFailuresAreWrappedInConfigLoadException() {
    assertThatThrownBy(() -> loader.load(MISSING_REQUIRED_ROOT))
        .isInstanceOf(ConfigLoadException.class)
        .satisfies(
            ex -> {
              ConfigLoadException cle = (ConfigLoadException) ex;
              assertThat(cle.getCause()).isInstanceOf(ConstraintViolationException.class);
              assertThat(cle.getMessage()).contains("displayName");
            });
  }

  @Test
  void missingResourceProducesConfigLoadException() {
    assertThatThrownBy(() -> loader.load("classpath:loader-fixtures/no-such-root/"))
        .isInstanceOf(ConfigLoadException.class)
        .hasMessageContaining("resource not found");
  }

  @Test
  void blankResourceRootIsRejected() {
    assertThatThrownBy(() -> loader.load(""))
        .isInstanceOf(ConfigLoadException.class)
        .hasMessageContaining("resource root must not be blank");
  }
}
