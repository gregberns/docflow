package com.docflow.c3.eval;

import com.docflow.Application;
import com.docflow.c3.eval.EvalScorer.AggregateScore;
import com.docflow.c3.eval.EvalScorer.SampleScore;
import com.docflow.c3.llm.LlmClassifier;
import com.docflow.c3.llm.LlmExtractor;
import com.docflow.config.AppConfig;
import com.github.f4b6a3.uuid.UuidCreator;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * C3 eval harness entry point. Boots a non-web Spring context, runs classify+extract over each
 * manifest entry against the live Anthropic API, scores the results with {@link EvalScorer}, and
 * writes a markdown report to {@code AppConfig.llm.eval.reportPath} (resolved relative to the
 * current working directory).
 *
 * <p>Per spec C3-R8 / C3-R9: live API only, no recorded-replay mode, never run in CI; aggregate
 * accuracy numbers only.
 */
public final class EvalRunner {

  private static final Logger LOG = LoggerFactory.getLogger(EvalRunner.class);

  private static final String MANIFEST_RESOURCE = "eval/manifest.yaml";
  private static final String SAMPLES_ROOT_PROPERTY = "docflow.eval.samplesRoot";
  private static final String DEFAULT_SAMPLES_ROOT = "../problem-statement/samples";
  private static final String EVAL_STORAGE_PATH = "(eval-runner)";
  private static final String MIME_PDF = "application/pdf";
  private static final String CURRENT_STEP_EXTRACTING = "EXTRACTING";
  private static final int RATE_LIMIT_BACKOFF_MS = 5_000;

  private final LlmClassifier classifier;
  private final LlmExtractor extractor;
  private final JdbcTemplate jdbc;
  private final EvalScorer scorer;
  private final EvalReportWriter reportWriter;
  private final Path samplesRoot;
  private final Path reportPath;
  private final Clock clock;

  public EvalRunner(
      LlmClassifier classifier,
      LlmExtractor extractor,
      JdbcTemplate jdbc,
      EvalScorer scorer,
      EvalReportWriter reportWriter,
      Path samplesRoot,
      Path reportPath,
      Clock clock) {
    this.classifier = classifier;
    this.extractor = extractor;
    this.jdbc = jdbc;
    this.scorer = scorer;
    this.reportWriter = reportWriter;
    this.samplesRoot = samplesRoot;
    this.reportPath = reportPath;
    this.clock = clock;
  }

  public static void main(String[] args) {
    SpringApplication app = new SpringApplication(Application.class);
    app.setWebApplicationType(WebApplicationType.NONE);
    app.setRegisterShutdownHook(false);
    int exitCode;
    try (ConfigurableApplicationContext ctx = app.run(args)) {
      AppConfig appConfig = ctx.getBean(AppConfig.class);
      Path samplesRoot = resolveSamplesRoot();
      Path reportPath = Path.of(appConfig.llm().eval().reportPath()).toAbsolutePath();
      EvalRunner runner =
          new EvalRunner(
              ctx.getBean(LlmClassifier.class),
              ctx.getBean(LlmExtractor.class),
              ctx.getBean(JdbcTemplate.class),
              new EvalScorer(),
              new EvalReportWriter(),
              samplesRoot,
              reportPath,
              Clock.systemUTC());
      exitCode = runner.run();
    }
    System.exit(exitCode);
  }

  public int run() {
    LOG.info(
        "EvalRunner: samplesRoot={}, reportPath={}",
        samplesRoot.toAbsolutePath(),
        reportPath.toAbsolutePath());
    List<EvalManifestEntry> entries = loadManifest();
    LOG.info("EvalRunner: loaded {} manifest entries", entries.size());

    preflight(entries);

    List<SampleScore> samples = new ArrayList<>(entries.size());
    boolean complete = true;
    for (EvalManifestEntry entry : entries) {
      try {
        samples.add(runSample(entry));
      } catch (RuntimeException e) {
        LOG.error("EvalRunner: aborting after failure on {}: {}", entry.path(), e.getMessage());
        samples.add(missingSample(entry));
        complete = false;
        break;
      }
    }

    AggregateScore aggregate = scorer.aggregate(samples);
    String markdown = reportWriter.render(samples, aggregate, Instant.now(clock), complete);
    writeReport(markdown);
    LOG.info(
        "EvalRunner: classification={}/{}, fields={}/{} ({})",
        aggregate.classifyHit(),
        aggregate.classifyTotal(),
        aggregate.fieldsMatched(),
        aggregate.fieldsTotal(),
        complete ? "complete" : "INCOMPLETE");
    return complete ? 0 : 1;
  }

  private SampleScore runSample(EvalManifestEntry entry) {
    Path samplePath = samplesRoot.resolve(entry.path());
    byte[] pdfBytes = readBytes(samplePath);
    String rawText = extractText(pdfBytes, entry.path());

    UUID storedDocumentId = ensureStoredDocument(entry);
    UUID processingDocumentId = insertProcessingDocument(storedDocumentId, entry);

    String predictedDocType = null;
    Map<String, Object> predictedFields = Map.of();
    try {
      predictedDocType =
          classifyWithBackoff(storedDocumentId, processingDocumentId, entry, rawText);
      predictedFields =
          extractWithBackoff(
              storedDocumentId, processingDocumentId, entry, predictedDocType, rawText);
    } catch (RuntimeException e) {
      LOG.warn("EvalRunner: sample {} failed during LLM call: {}", entry.path(), e.getMessage());
    }
    return scorer.score(
        entry.path(),
        entry.documentType(),
        predictedDocType,
        entry.extractedFields(),
        predictedFields);
  }

  private String classifyWithBackoff(
      UUID storedDocumentId, UUID processingDocumentId, EvalManifestEntry entry, String rawText) {
    try {
      return classifier
          .classify(storedDocumentId, processingDocumentId, entry.organizationId(), rawText)
          .detectedDocumentType();
    } catch (RuntimeException first) {
      sleep();
      return classifier
          .classify(storedDocumentId, processingDocumentId, entry.organizationId(), rawText)
          .detectedDocumentType();
    }
  }

  private Map<String, Object> extractWithBackoff(
      UUID storedDocumentId,
      UUID processingDocumentId,
      EvalManifestEntry entry,
      String docTypeId,
      String rawText) {
    try {
      return extractor.extractFields(
          storedDocumentId, processingDocumentId, entry.organizationId(), docTypeId, rawText);
    } catch (RuntimeException first) {
      sleep();
      return extractor.extractFields(
          storedDocumentId, processingDocumentId, entry.organizationId(), docTypeId, rawText);
    }
  }

  private void sleep() {
    try {
      Thread.sleep(RATE_LIMIT_BACKOFF_MS);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while backing off", ie);
    }
  }

  private SampleScore missingSample(EvalManifestEntry entry) {
    return scorer.score(
        entry.path(), entry.documentType(), null, entry.extractedFields(), Map.of());
  }

  private List<EvalManifestEntry> loadManifest() {
    ObjectMapper yamlMapper = YAMLMapper.builder().build();
    try (InputStream in = openClasspath()) {
      return yamlMapper.readValue(in, new TypeReference<List<EvalManifestEntry>>() {});
    } catch (IOException e) {
      throw new IllegalStateException("failed to load eval manifest " + MANIFEST_RESOURCE, e);
    }
  }

  private InputStream openClasspath() throws IOException {
    InputStream in =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(MANIFEST_RESOURCE);
    if (in == null) {
      throw new IOException("eval manifest not found on classpath: " + MANIFEST_RESOURCE);
    }
    return in;
  }

  private void preflight(List<EvalManifestEntry> entries) {
    List<String> missing = new ArrayList<>();
    for (EvalManifestEntry entry : entries) {
      Path p = samplesRoot.resolve(entry.path());
      if (!Files.exists(p)) {
        missing.add(p.toString());
      }
    }
    if (!missing.isEmpty()) {
      throw new IllegalStateException("manifest references missing sample files: " + missing);
    }
  }

  private UUID ensureStoredDocument(EvalManifestEntry entry) {
    List<UUID> existing =
        jdbc.queryForList(
            "SELECT id FROM stored_documents WHERE organization_id = ? AND source_filename = ?",
            UUID.class,
            entry.organizationId(),
            entry.path());
    if (!existing.isEmpty()) {
      return existing.get(0);
    }
    UUID id = UuidCreator.getTimeOrderedEpoch();
    jdbc.update(
        "INSERT INTO stored_documents "
            + "(id, organization_id, uploaded_at, source_filename, mime_type, storage_path) "
            + "VALUES (?, ?, ?, ?, ?, ?)",
        id,
        entry.organizationId(),
        Timestamp.from(Instant.now(clock)),
        entry.path(),
        MIME_PDF,
        EVAL_STORAGE_PATH);
    return id;
  }

  private UUID insertProcessingDocument(UUID storedDocumentId, EvalManifestEntry entry) {
    UUID id = UuidCreator.getTimeOrderedEpoch();
    jdbc.update(
        "INSERT INTO processing_documents "
            + "(id, stored_document_id, organization_id, current_step) "
            + "VALUES (?, ?, ?, ?)",
        id,
        storedDocumentId,
        entry.organizationId(),
        CURRENT_STEP_EXTRACTING);
    return id;
  }

  private static byte[] readBytes(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException e) {
      throw new IllegalStateException("failed to read sample " + path, e);
    }
  }

  private static String extractText(byte[] pdfBytes, String label) {
    try (PDDocument document = Loader.loadPDF(pdfBytes)) {
      return new PDFTextStripper().getText(document);
    } catch (IOException e) {
      throw new IllegalStateException(
          "text-extract failed for " + label + ": " + e.getMessage(), e);
    }
  }

  private void writeReport(String markdown) {
    try {
      Path parent = reportPath.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(reportPath, markdown, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("failed to write eval report " + reportPath, e);
    }
  }

  private static Path resolveSamplesRoot() {
    String override = System.getProperty(SAMPLES_ROOT_PROPERTY);
    String raw = override == null || override.isBlank() ? DEFAULT_SAMPLES_ROOT : override;
    return Path.of(raw).toAbsolutePath().normalize();
  }
}
