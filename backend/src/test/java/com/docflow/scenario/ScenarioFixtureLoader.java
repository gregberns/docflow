package com.docflow.scenario;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

public final class ScenarioFixtureLoader {

  private static final Set<String> ALLOWED_ERRORS =
      Set.of("SCHEMA_VIOLATION", "UNAVAILABLE", "TIMEOUT", "PROTOCOL_ERROR");

  private final ObjectMapper yamlMapper;

  public ScenarioFixtureLoader() {
    this(strictMapper());
  }

  ScenarioFixtureLoader(ObjectMapper yamlMapper) {
    this.yamlMapper = yamlMapper;
  }

  public ScenarioFixture load(Path file) {
    try (InputStream in = Files.newInputStream(file)) {
      return validate(yamlMapper.readValue(in, ScenarioFixture.class), file.toString());
    } catch (JacksonException e) {
      throw new ScenarioFixtureLoadException(
          "failed to parse scenario fixture " + file + ": " + e.getOriginalMessage(), e);
    } catch (IOException e) {
      throw new ScenarioFixtureLoadException("failed to read scenario fixture " + file, e);
    }
  }

  public List<ScenarioFixture> loadAll(Path directory) {
    if (!Files.isDirectory(directory)) {
      throw new ScenarioFixtureLoadException(
          "scenario fixture directory does not exist: " + directory);
    }
    try (Stream<Path> entries = Files.list(directory)) {
      return entries
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".yaml"))
          .filter(p -> !p.getFileName().toString().startsWith("_"))
          .sorted(Comparator.comparing(p -> p.getFileName().toString()))
          .map(this::load)
          .toList();
    } catch (IOException e) {
      throw new ScenarioFixtureLoadException(
          "failed to list scenario fixture directory " + directory, e);
    }
  }

  public ScenarioFixture loadFromString(String yaml, String sourceLabel) {
    try {
      return validate(yamlMapper.readValue(yaml, ScenarioFixture.class), sourceLabel);
    } catch (JacksonException e) {
      throw new ScenarioFixtureLoadException(
          "failed to parse scenario fixture " + sourceLabel + ": " + e.getOriginalMessage(), e);
    }
  }

  private static ScenarioFixture validate(ScenarioFixture fixture, String source) {
    if (fixture.scenarioId() == null || fixture.scenarioId().isBlank()) {
      throw new ScenarioFixtureLoadException(source + ": scenarioId must be present");
    }
    boolean hasInputPdf = fixture.inputPdf() != null && !fixture.inputPdf().isBlank();
    boolean hasInputs = fixture.inputs() != null && !fixture.inputs().isEmpty();
    if (!hasInputPdf && !hasInputs) {
      throw new ScenarioFixtureLoadException(
          source + ": fixture must declare either 'inputPdf' or non-empty 'inputs'");
    }
    if (hasInputPdf && hasInputs) {
      throw new ScenarioFixtureLoadException(
          source + ": fixture must declare exactly one of 'inputPdf' or 'inputs', not both");
    }
    if (hasInputPdf) {
      validateClassification(fixture.classification(), source, "classification");
      validateExtraction(fixture.extraction(), source, "extraction");
    } else {
      for (int i = 0; i < fixture.inputs().size(); i++) {
        ScenarioFixture.Input input = fixture.inputs().get(i);
        if (input.inputPdf() == null || input.inputPdf().isBlank()) {
          throw new ScenarioFixtureLoadException(
              source + ": inputs[" + i + "].inputPdf must be present");
        }
        validateClassification(input.classification(), source, "inputs[" + i + "].classification");
        validateExtraction(input.extraction(), source, "inputs[" + i + "].extraction");
      }
    }
    return fixture;
  }

  private static void validateClassification(
      ScenarioFixture.Classification c, String source, String path) {
    if (c == null) {
      throw new ScenarioFixtureLoadException(source + ": " + path + " must be present");
    }
    boolean hasDocType = c.docType() != null && !c.docType().isBlank();
    boolean hasError = c.error() != null && !c.error().isBlank();
    if (!hasDocType && !hasError) {
      throw new ScenarioFixtureLoadException(
          source + ": " + path + " must specify either 'docType' or 'error'");
    }
    if (hasDocType && hasError) {
      throw new ScenarioFixtureLoadException(
          source + ": " + path + " must specify exactly one of 'docType' or 'error'");
    }
    if (hasError && !ALLOWED_ERRORS.contains(c.error())) {
      throw new ScenarioFixtureLoadException(
          source + ": " + path + ".error '" + c.error() + "' is not one of " + ALLOWED_ERRORS);
    }
  }

  private static void validateExtraction(ScenarioFixture.Extraction e, String source, String path) {
    if (e == null) {
      return;
    }
    boolean hasFields = e.fields() != null;
    boolean hasError = e.error() != null && !e.error().isBlank();
    if (hasFields && hasError) {
      throw new ScenarioFixtureLoadException(
          source + ": " + path + " must specify at most one of 'fields' or 'error'");
    }
    if (hasError && !ALLOWED_ERRORS.contains(e.error())) {
      throw new ScenarioFixtureLoadException(
          source + ": " + path + ".error '" + e.error() + "' is not one of " + ALLOWED_ERRORS);
    }
  }

  private static ObjectMapper strictMapper() {
    return YAMLMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
  }

  public static final class ScenarioFixtureLoadException extends RuntimeException {

    public ScenarioFixtureLoadException(String message) {
      super(message);
    }

    public ScenarioFixtureLoadException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
