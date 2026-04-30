package com.docflow.platform;

import com.docflow.config.AppConfig;
import com.docflow.document.Document;
import com.docflow.document.ReextractionStatus;
import com.docflow.ingestion.StoredDocument;
import com.docflow.ingestion.StoredDocumentId;
import com.docflow.ingestion.StoredDocumentWriter;
import com.docflow.ingestion.storage.StoredDocumentStorage;
import com.docflow.workflow.WorkflowInstance;
import com.docflow.workflow.WorkflowInstanceWriter;
import com.docflow.workflow.WorkflowStatus;
import com.github.f4b6a3.uuid.UuidCreator;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Application-data seeder (C7-R4). Runs after {@code OrgConfigSeeder} has populated reference
 * tables; reads {@code seed/manifest.yaml} and inserts {@link StoredDocument}, {@link Document},
 * and {@link WorkflowInstance} rows for each entry. Idempotent on {@code (organizationId,
 * sourcePath)} via {@code stored_documents.source_filename}.
 */
// why: per-row construction of ClassPathResource / WorkflowInstance / EntryKey is fundamental to
// seeding 12 manifest entries; same precedent as OrgConfigSeedWriter.
@SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
@Component
@ConditionalOnBean(WorkflowInstanceWriter.class)
public class SeedDataLoader {

  private static final Logger LOG = LoggerFactory.getLogger(SeedDataLoader.class);
  private static final String STAGE_KIND_REVIEW = "REVIEW";
  private static final String SEED_FILES_ROOT = "seed/files/";
  private static final String SEED_MANIFEST_RESOURCE = "seed/manifest.yaml";
  private static final String MIME_PDF = "application/pdf";
  private static final String FILE_SUFFIX = ".bin";

  private final StoredDocumentStorage storage;
  private final StoredDocumentWriter storedDocumentWriter;
  private final WorkflowInstanceWriter workflowInstanceWriter;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactionTemplate;
  private final Path storageRoot;
  private final boolean seedOnBoot;

  public SeedDataLoader(
      StoredDocumentStorage storage,
      StoredDocumentWriter storedDocumentWriter,
      WorkflowInstanceWriter workflowInstanceWriter,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      AppConfig appConfig) {
    this.storage = storage;
    this.storedDocumentWriter = storedDocumentWriter;
    this.workflowInstanceWriter = workflowInstanceWriter;
    this.jdbc = jdbc;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.storageRoot = Path.of(appConfig.storage().storageRoot());
    this.seedOnBoot = appConfig.config().seedOnBoot();
  }

  @EventListener(ApplicationReadyEvent.class)
  @Order(Ordered.HIGHEST_PRECEDENCE + 200)
  public void seedOnReady() {
    if (!seedOnBoot) {
      LOG.info("SeedDataLoader: seedOnBoot=false, skipping");
      return;
    }
    List<SeedManifestEntry> entries = readManifest();
    LOG.info("SeedDataLoader: read {} manifest entries", entries.size());

    Map<EntryKey, byte[]> preflighted = preflightLoadAll(entries);

    int seeded = 0;
    int skipped = 0;
    for (SeedManifestEntry entry : entries) {
      if (alreadySeeded(entry)) {
        skipped++;
        continue;
      }
      byte[] bytes = preflighted.get(EntryKey.of(entry));
      seedEntry(entry, bytes);
      seeded++;
    }
    LOG.info("SeedDataLoader: seeded {} new entries, skipped {} existing", seeded, skipped);
  }

  private List<SeedManifestEntry> readManifest() {
    ObjectMapper yamlMapper = YAMLMapper.builder().build();
    try (InputStream in = openClasspath(SEED_MANIFEST_RESOURCE)) {
      return yamlMapper.readValue(in, new TypeReference<List<SeedManifestEntry>>() {});
    } catch (JacksonException | IOException e) {
      throw new IllegalStateException("Failed to read seed manifest " + SEED_MANIFEST_RESOURCE, e);
    }
  }

  private Map<EntryKey, byte[]> preflightLoadAll(List<SeedManifestEntry> entries) {
    Map<EntryKey, byte[]> bytesByEntry = new LinkedHashMap<>();
    List<String> missing = new ArrayList<>();
    for (SeedManifestEntry entry : entries) {
      String resourcePath = SEED_FILES_ROOT + entry.path();
      ClassPathResource resource = new ClassPathResource(resourcePath);
      if (!resource.exists()) {
        missing.add(entry.path());
        continue;
      }
      try (InputStream in = resource.getInputStream()) {
        bytesByEntry.put(EntryKey.of(entry), in.readAllBytes());
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to read seed file " + resourcePath, e);
      }
    }
    if (!missing.isEmpty()) {
      throw new IllegalStateException("seed file not found: " + String.join(", ", missing));
    }
    return bytesByEntry;
  }

  private boolean alreadySeeded(SeedManifestEntry entry) {
    Long count =
        jdbc.queryForObject(
            "SELECT count(*) FROM stored_documents "
                + "WHERE organization_id = ? AND source_filename = ?",
            Long.class,
            entry.organizationId(),
            entry.path());
    return count != null && count > 0;
  }

  private void seedEntry(SeedManifestEntry entry, byte[] bytes) {
    String reviewStageId = lookupReviewStageId(entry.organizationId(), entry.documentType());

    StoredDocumentId storedId = StoredDocumentId.generate();
    UUID documentId = UuidCreator.getTimeOrderedEpoch();
    UUID workflowInstanceId = UuidCreator.getTimeOrderedEpoch();
    Instant now = Instant.now();
    String storagePath = storageRoot.resolve(storedId.value() + FILE_SUFFIX).toString();

    storage.save(storedId, bytes);

    StoredDocument storedDocument =
        new StoredDocument(
            storedId, entry.organizationId(), now, entry.path(), MIME_PDF, storagePath);

    transactionTemplate.executeWithoutResult(
        status -> {
          storedDocumentWriter.insert(storedDocument);

          jdbc.update(
              "INSERT INTO documents "
                  + "(id, stored_document_id, organization_id, detected_document_type, "
                  + "extracted_fields, raw_text, processed_at, reextraction_status) "
                  + "VALUES (?, ?, ?, ?, ?::jsonb, NULL, ?, ?)",
              documentId,
              storedId.value(),
              entry.organizationId(),
              entry.documentType(),
              toJsonString(entry.extractedFields()),
              Timestamp.from(now),
              ReextractionStatus.NONE.name());

          WorkflowInstance instance =
              new WorkflowInstance(
                  workflowInstanceId,
                  documentId,
                  entry.organizationId(),
                  reviewStageId,
                  WorkflowStatus.AWAITING_REVIEW,
                  null,
                  null,
                  now);
          workflowInstanceWriter.insert(instance, entry.documentType());
        });
  }

  private String lookupReviewStageId(String orgId, String docTypeId) {
    List<String> ids =
        jdbc.queryForList(
            "SELECT id FROM stages "
                + "WHERE organization_id = ? AND document_type_id = ? AND kind = ? "
                + "ORDER BY ordinal ASC",
            String.class,
            orgId,
            docTypeId,
            STAGE_KIND_REVIEW);
    if (ids.isEmpty()) {
      throw new IllegalStateException(
          "no review stage registered for org=" + orgId + " docType=" + docTypeId);
    }
    return ids.get(0);
  }

  private static String toJsonString(Map<String, Object> fields) {
    ObjectMapper jsonMapper = JsonMapper.builder().build();
    return jsonMapper.writeValueAsString(fields == null ? Map.of() : fields);
  }

  private static InputStream openClasspath(String path) {
    InputStream in = SeedDataLoader.class.getClassLoader().getResourceAsStream(path);
    if (in == null) {
      throw new IllegalStateException("seed manifest not found on classpath: " + path);
    }
    return in;
  }

  private record EntryKey(String organizationId, String path) {
    static EntryKey of(SeedManifestEntry entry) {
      return new EntryKey(entry.organizationId(), entry.path());
    }
  }
}
