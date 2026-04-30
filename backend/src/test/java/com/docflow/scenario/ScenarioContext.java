package com.docflow.scenario;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

public class ScenarioContext {

  private static final String SAMPLES_ROOT = "problem-statement/samples/";
  private static final String FIXTURES_ROOT = "backend/src/test/resources/scenarios/_fixtures/";

  private final Map<String, String> rawTextIndex = new ConcurrentHashMap<>();
  private volatile List<ScenarioFixture> active = List.of();

  public static java.util.Optional<Path> tryResolve(String pdfPath) {
    for (Path p : candidates(pdfPath)) {
      if (Files.exists(p)) {
        return java.util.Optional.of(p.toAbsolutePath().normalize());
      }
    }
    return java.util.Optional.empty();
  }

  public static Path canonicalAbsolutePath(String pdfPath) {
    String rootPrefix = pdfPath.startsWith("_fixtures/") ? FIXTURES_ROOT : SAMPLES_ROOT;
    String suffix =
        pdfPath.startsWith("_fixtures/") ? pdfPath.substring("_fixtures/".length()) : pdfPath;
    Path rootPrimary = Paths.get(rootPrefix);
    Path rootParent = Paths.get("../" + rootPrefix);
    Path base;
    if (Files.isDirectory(rootPrimary)) {
      base = rootPrimary;
    } else if (Files.isDirectory(rootParent)) {
      base = rootParent;
    } else {
      base = rootPrimary;
    }
    return base.resolve(suffix).toAbsolutePath().normalize();
  }

  public synchronized void setActive(ScenarioFixture fixture) {
    Objects.requireNonNull(fixture, "fixture");
    setActive(List.of(fixture));
  }

  public synchronized void setActive(List<ScenarioFixture> fixtures) {
    Objects.requireNonNull(fixtures, "fixtures");
    if (fixtures.isEmpty()) {
      throw new IllegalArgumentException("fixtures must not be empty");
    }
    rawTextIndex.clear();
    this.active = List.copyOf(fixtures);
  }

  public synchronized void clear() {
    rawTextIndex.clear();
    this.active = List.of();
  }

  public List<ScenarioFixture> active() {
    return active;
  }

  public ScenarioFixture.Input matchByRawText(String rawText) {
    if (active.isEmpty()) {
      throw new IllegalStateException("no scenario fixture is active");
    }
    if (active.size() == 1
        && (active.get(0).inputs() == null || active.get(0).inputs().isEmpty())) {
      ScenarioFixture only = active.get(0);
      return new ScenarioFixture.Input(
          only.inputPdf(), only.organizationId(), only.classification(), only.extraction());
    }
    for (ScenarioFixture fixture : active) {
      if (fixture.inputs() != null) {
        for (ScenarioFixture.Input input : fixture.inputs()) {
          String indexed = rawTextIndex.computeIfAbsent(input.inputPdf(), this::extractText);
          if (indexed.equals(rawText)) {
            return input;
          }
        }
      } else if (fixture.inputPdf() != null) {
        String indexed = rawTextIndex.computeIfAbsent(fixture.inputPdf(), this::extractText);
        if (indexed.equals(rawText)) {
          return new ScenarioFixture.Input(
              fixture.inputPdf(),
              fixture.organizationId(),
              fixture.classification(),
              fixture.extraction());
        }
      }
    }
    throw new IllegalStateException(
        "no scenario fixture matched rawText (length=" + rawText.length() + ")");
  }

  private String extractText(String pdfPath) {
    Path resolved = resolve(pdfPath);
    try (PDDocument doc = Loader.loadPDF(Files.readAllBytes(resolved))) {
      return new PDFTextStripper().getText(doc);
    } catch (Exception e) {
      throw new IllegalStateException("failed to extract text from " + resolved, e);
    }
  }

  private static Path resolve(String pdfPath) {
    for (Path p : candidates(pdfPath)) {
      if (Files.exists(p)) {
        return p;
      }
    }
    throw new IllegalStateException(
        "could not resolve PDF path '" + pdfPath + "' under known roots");
  }

  private static Path[] candidates(String pdfPath) {
    if (pdfPath.startsWith("_fixtures/")) {
      String suffix = pdfPath.substring("_fixtures/".length());
      return new Path[] {
        Paths.get(pdfPath),
        Paths.get(FIXTURES_ROOT + suffix),
        Paths.get("../" + FIXTURES_ROOT + suffix)
      };
    }
    return new Path[] {
      Paths.get(pdfPath),
      Paths.get(SAMPLES_ROOT + pdfPath),
      Paths.get("../" + SAMPLES_ROOT + pdfPath),
      Paths.get(FIXTURES_ROOT + pdfPath),
      Paths.get("../" + FIXTURES_ROOT + pdfPath)
    };
  }
}
