package com.docflow.ingestion.internal;

import com.docflow.api.error.UnknownOrganizationException;
import com.docflow.c3.events.StoredDocumentIngested;
import com.docflow.config.AppConfig;
import com.docflow.config.catalog.OrganizationCatalog;
import com.docflow.ingestion.IngestionResult;
import com.docflow.ingestion.StoredDocumentId;
import com.docflow.ingestion.StoredDocumentIngestionService;
import com.docflow.ingestion.UnsupportedMediaTypeException;
import com.docflow.ingestion.storage.StoredDocumentStorage;
import com.docflow.platform.DocumentEventBus;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.apache.tika.detect.Detector;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

// @ConditionalOnBean here is a test-only accommodation: a handful of integration tests
// load a narrow Spring slice that intentionally excludes OrganizationCatalog. In production,
// OrganizationCatalog is always present, so this conditional is a no-op at runtime.
@Service
@ConditionalOnBean(OrganizationCatalog.class)
class StoredDocumentIngestionServiceImpl implements StoredDocumentIngestionService {

  private static final String OCTET_STREAM = "application/octet-stream";
  private static final String INITIAL_STEP = "TEXT_EXTRACTING";
  private static final String FILE_SUFFIX = ".bin";

  private static final Set<String> ALLOWED = Set.of("application/pdf", "image/png", "image/jpeg");

  private final OrganizationCatalog organizationCatalog;
  private final Detector detector;
  private final StoredDocumentStorage storage;
  private final JdbcTemplate jdbcTemplate;
  private final DocumentEventBus eventBus;
  private final TransactionTemplate transactionTemplate;
  private final Path storageRoot;

  StoredDocumentIngestionServiceImpl(
      OrganizationCatalog organizationCatalog,
      Detector detector,
      StoredDocumentStorage storage,
      JdbcTemplate jdbcTemplate,
      DocumentEventBus eventBus,
      PlatformTransactionManager transactionManager,
      AppConfig appConfig) {
    this.organizationCatalog = organizationCatalog;
    this.detector = detector;
    this.storage = storage;
    this.jdbcTemplate = jdbcTemplate;
    this.eventBus = eventBus;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.storageRoot = Path.of(appConfig.storage().storageRoot());
  }

  @Override
  public IngestionResult upload(
      String organizationId, String sourceFilename, String claimedContentType, byte[] bytes) {
    if (organizationCatalog.getOrganization(organizationId).isEmpty()) {
      throw new UnknownOrganizationException(organizationId);
    }

    if (bytes == null || bytes.length == 0) {
      throw new UnsupportedMediaTypeException(OCTET_STREAM);
    }

    String mimeType = sniff(bytes, sourceFilename, claimedContentType);

    StoredDocumentId storedId = StoredDocumentId.generate();
    UUID processingId = UUID.randomUUID();
    Instant uploadedAt = Instant.now();
    String storagePath = storageRoot.resolve(storedId.value() + FILE_SUFFIX).toString();

    storage.save(storedId, bytes);

    StoredDocumentIngested event =
        new StoredDocumentIngested(storedId.value(), organizationId, processingId, uploadedAt);

    transactionTemplate.executeWithoutResult(
        status -> {
          jdbcTemplate.update(
              "INSERT INTO stored_documents "
                  + "(id, organization_id, uploaded_at, source_filename, mime_type, storage_path) "
                  + "VALUES (?, ?, ?, ?, ?, ?)",
              storedId.value(),
              organizationId,
              java.sql.Timestamp.from(uploadedAt),
              sourceFilename,
              mimeType,
              storagePath);

          jdbcTemplate.update(
              "INSERT INTO processing_documents "
                  + "(id, stored_document_id, organization_id, current_step, raw_text, "
                  + "last_error, created_at) VALUES (?, ?, ?, ?, NULL, NULL, ?)",
              processingId,
              storedId.value(),
              organizationId,
              INITIAL_STEP,
              java.sql.Timestamp.from(uploadedAt));

          TransactionSynchronizationManager.registerSynchronization(
              new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                  eventBus.publish(event);
                }
              });
        });

    return new IngestionResult(storedId.value(), processingId);
  }

  private String sniff(byte[] bytes, String claimedFilename, String claimedContentType) {
    Metadata metadata = new Metadata();
    if (claimedFilename != null) {
      metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, claimedFilename);
    }
    if (claimedContentType != null) {
      metadata.set(Metadata.CONTENT_TYPE, claimedContentType);
    }

    String sniffed;
    try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
      MediaType detected = detector.detect(in, metadata);
      sniffed = detected == null ? OCTET_STREAM : detected.getBaseType().toString();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to sniff MIME for upload", e);
    }

    if (ALLOWED.contains(sniffed)) {
      return sniffed;
    }
    if (OCTET_STREAM.equals(sniffed)
        && claimedContentType != null
        && ALLOWED.contains(claimedContentType)) {
      return claimedContentType;
    }
    throw new UnsupportedMediaTypeException(sniffed);
  }
}
