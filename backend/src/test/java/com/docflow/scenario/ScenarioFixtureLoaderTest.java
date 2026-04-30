package com.docflow.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docflow.scenario.ScenarioFixtureLoader.ScenarioFixtureLoadException;
import org.junit.jupiter.api.Test;

class ScenarioFixtureLoaderTest {

  private final ScenarioFixtureLoader loader = new ScenarioFixtureLoader();

  @Test
  void rejectsUnknownYamlProperty() {
    String yaml =
        """
        scenarioId: unknown-prop
        organizationId: pinnacle-legal
        inputPdf: pinnacle-legal/invoices/dewey_cheatham_howe_feb_2024.pdf
        classification:
          docType: invoice
        extraction:
          fields:
            vendor: "X"
        bogusField: oops
        """;
    assertThatThrownBy(() -> loader.loadFromString(yaml, "unknown-prop.yaml"))
        .isInstanceOf(ScenarioFixtureLoadException.class)
        .hasMessageContaining("bogusField");
  }

  @Test
  void rejectsFixtureMissingBothInputPdfAndInputs() {
    String yaml =
        """
        scenarioId: missing-input
        organizationId: pinnacle-legal
        classification:
          docType: invoice
        extraction:
          fields:
            vendor: "X"
        """;
    assertThatThrownBy(() -> loader.loadFromString(yaml, "missing-input.yaml"))
        .isInstanceOf(ScenarioFixtureLoadException.class)
        .hasMessageContaining("must declare either 'inputPdf' or non-empty 'inputs'");
  }

  @Test
  void rejectsFixtureWithBothInputPdfAndInputs() {
    String yaml =
        """
        scenarioId: both-inputs
        organizationId: pinnacle-legal
        inputPdf: pinnacle-legal/invoices/dewey_cheatham_howe_feb_2024.pdf
        inputs:
          - inputPdf: ironworks-construction/invoices/concrete_jungle_phase2_foundation_inv.pdf
            organizationId: pinnacle-legal
            classification:
              docType: invoice
            extraction:
              fields:
                vendor: "X"
        classification:
          docType: invoice
        extraction:
          fields:
            vendor: "X"
        """;
    assertThatThrownBy(() -> loader.loadFromString(yaml, "both-inputs.yaml"))
        .isInstanceOf(ScenarioFixtureLoadException.class)
        .hasMessageContaining("exactly one of 'inputPdf' or 'inputs'");
  }

  @Test
  void rejectsClassificationMissingBothDocTypeAndError() {
    String yaml =
        """
        scenarioId: empty-classification
        organizationId: pinnacle-legal
        inputPdf: pinnacle-legal/invoices/dewey_cheatham_howe_feb_2024.pdf
        classification: {}
        extraction:
          fields:
            vendor: "X"
        """;
    assertThatThrownBy(() -> loader.loadFromString(yaml, "empty-classification.yaml"))
        .isInstanceOf(ScenarioFixtureLoadException.class)
        .hasMessageContaining("must specify either 'docType' or 'error'");
  }

  @Test
  void rejectsClassificationWithUnknownErrorName() {
    String yaml =
        """
        scenarioId: bad-error-name
        organizationId: pinnacle-legal
        inputPdf: pinnacle-legal/invoices/dewey_cheatham_howe_feb_2024.pdf
        classification:
          error: NUCLEAR_MELTDOWN
        extraction:
          fields:
            vendor: "X"
        """;
    assertThatThrownBy(() -> loader.loadFromString(yaml, "bad-error-name.yaml"))
        .isInstanceOf(ScenarioFixtureLoadException.class)
        .hasMessageContaining("NUCLEAR_MELTDOWN")
        .hasMessageContaining("not one of");
  }

  @Test
  void rejectsClassificationWithBothDocTypeAndError() {
    String yaml =
        """
        scenarioId: both-classification-fields
        organizationId: pinnacle-legal
        inputPdf: pinnacle-legal/invoices/dewey_cheatham_howe_feb_2024.pdf
        classification:
          docType: invoice
          error: SCHEMA_VIOLATION
        extraction:
          fields:
            vendor: "X"
        """;
    assertThatThrownBy(() -> loader.loadFromString(yaml, "both-classification-fields.yaml"))
        .isInstanceOf(ScenarioFixtureLoadException.class)
        .hasMessageContaining("exactly one of 'docType' or 'error'");
  }

  @Test
  void acceptsMinimalValidFixture() {
    String yaml =
        """
        scenarioId: minimal-valid
        organizationId: pinnacle-legal
        inputPdf: pinnacle-legal/invoices/dewey_cheatham_howe_feb_2024.pdf
        classification:
          docType: invoice
        extraction:
          fields:
            vendor: "Acme"
        """;
    ScenarioFixture fixture = loader.loadFromString(yaml, "minimal-valid.yaml");
    assertThat(fixture.scenarioId()).isEqualTo("minimal-valid");
    assertThat(fixture.classification().docType()).isEqualTo("invoice");
    assertThat(fixture.extraction().fields()).containsEntry("vendor", "Acme");
  }

  @Test
  void acceptsClassificationErrorFixture() {
    String yaml =
        """
        scenarioId: classification-error
        organizationId: pinnacle-legal
        inputPdf: pinnacle-legal/invoices/dewey_cheatham_howe_feb_2024.pdf
        classification:
          error: SCHEMA_VIOLATION
        extraction:
          fields:
            vendor: "Acme"
        """;
    ScenarioFixture fixture = loader.loadFromString(yaml, "classification-error.yaml");
    assertThat(fixture.classification().error()).isEqualTo("SCHEMA_VIOLATION");
  }

  @Test
  void acceptsMultiInputFixture() {
    String yaml =
        """
        scenarioId: multi-input
        inputs:
          - inputPdf: ironworks-construction/invoices/concrete_jungle_phase2_foundation_inv.pdf
            organizationId: ironworks-construction
            classification:
              docType: invoice
            extraction:
              fields:
                vendor: "A"
          - inputPdf: ironworks-construction/invoices/exotic_aquatic_moat_materials_inv.pdf
            organizationId: ironworks-construction
            classification:
              docType: invoice
            extraction:
              fields:
                vendor: "B"
        """;
    ScenarioFixture fixture = loader.loadFromString(yaml, "multi-input.yaml");
    assertThat(fixture.inputs()).hasSize(2);
    assertThat(fixture.inputs().get(0).inputPdf())
        .isEqualTo("ironworks-construction/invoices/concrete_jungle_phase2_foundation_inv.pdf");
  }

  @Test
  void rejectsFixtureWithMissingInputPdf_atLoadTimeWithAbsolutePath() {
    String missing = "pinnacle-legal/invoices/does_not_exist_42.pdf";
    String yaml =
        """
        scenarioId: missing-pdf
        organizationId: pinnacle-legal
        inputPdf: %s
        classification:
          docType: invoice
        extraction:
          fields:
            vendor: "X"
        """
            .formatted(missing);

    java.nio.file.Path expectedAbsolute = ScenarioContext.canonicalAbsolutePath(missing);

    assertThatThrownBy(() -> loader.loadFromString(yaml, "missing-pdf.yaml"))
        .isInstanceOf(ScenarioFixtureLoadException.class)
        .hasMessageContaining("could not be resolved")
        .hasMessageContaining(expectedAbsolute.toString());
  }

  @Test
  void rejectsExtractionWithBothFieldsAndError() {
    String yaml =
        """
        scenarioId: both-extraction-fields-and-error
        organizationId: pinnacle-legal
        inputPdf: pinnacle-legal/invoices/dewey_cheatham_howe_feb_2024.pdf
        classification:
          docType: invoice
        extraction:
          fields:
            vendor: "X"
          error: SCHEMA_VIOLATION
        """;
    assertThatThrownBy(() -> loader.loadFromString(yaml, "both.yaml"))
        .isInstanceOf(ScenarioFixtureLoadException.class)
        .hasMessageContaining("at most one of 'fields' or 'error'");
  }
}
