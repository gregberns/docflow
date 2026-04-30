package com.docflow.c3.pipeline.internal;

import com.docflow.c3.pipeline.ProcessingDocumentId;
import com.docflow.c3.pipeline.ProcessingDocumentNotFoundException;
import com.docflow.c3.pipeline.ProcessingDocumentOrganizationMismatchException;
import com.docflow.c3.pipeline.ProcessingDocumentWriter;
import com.docflow.ingestion.StoredDocument;
import com.docflow.ingestion.StoredDocumentId;
import com.docflow.ingestion.StoredDocumentReader;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcProcessingDocumentWriter implements ProcessingDocumentWriter {

  private static final String FAILED_STEP = "FAILED";

  private static final String INSERT_SQL =
      "INSERT INTO processing_documents "
          + "(id, stored_document_id, organization_id, current_step, raw_text, last_error, "
          + "created_at) "
          + "VALUES (:id, :storedDocumentId, :organizationId, :currentStep, NULL, NULL, "
          + ":createdAt)";

  private static final String UPDATE_STEP_SQL =
      "UPDATE processing_documents SET current_step = :currentStep, updated_at = now() "
          + "WHERE id = :id";

  private static final String UPDATE_RAW_TEXT_SQL =
      "UPDATE processing_documents SET raw_text = :rawText, updated_at = now() WHERE id = :id";

  private static final String UPDATE_FAILED_SQL =
      "UPDATE processing_documents SET current_step = :currentStep, last_error = :lastError, "
          + "updated_at = now() WHERE id = :id";

  private static final String SELECT_OWNER_SQL =
      "SELECT organization_id, stored_document_id FROM processing_documents WHERE id = :id";

  private final NamedParameterJdbcOperations jdbc;
  private final StoredDocumentReader storedDocumentReader;

  JdbcProcessingDocumentWriter(
      NamedParameterJdbcOperations jdbc, StoredDocumentReader storedDocumentReader) {
    this.jdbc = jdbc;
    this.storedDocumentReader = storedDocumentReader;
  }

  @Override
  public void insert(
      ProcessingDocumentId id,
      StoredDocumentId storedDocumentId,
      String organizationId,
      String currentStep,
      Instant createdAt) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(storedDocumentId, "storedDocumentId");
    Objects.requireNonNull(organizationId, "organizationId");
    Objects.requireNonNull(currentStep, "currentStep");
    Objects.requireNonNull(createdAt, "createdAt");
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("id", id.value())
            .addValue("storedDocumentId", storedDocumentId.value())
            .addValue("organizationId", organizationId)
            .addValue("currentStep", currentStep)
            .addValue("createdAt", Timestamp.from(createdAt));
    jdbc.update(INSERT_SQL, params);
  }

  @Override
  @Transactional
  public void updateStep(ProcessingDocumentId id, String currentStep) {
    Objects.requireNonNull(currentStep, "currentStep");
    assertOrgConsistent(id);
    MapSqlParameterSource params =
        new MapSqlParameterSource().addValue("id", id.value()).addValue("currentStep", currentStep);
    jdbc.update(UPDATE_STEP_SQL, params);
  }

  @Override
  @Transactional
  public void updateRawText(ProcessingDocumentId id, String rawText) {
    assertOrgConsistent(id);
    MapSqlParameterSource params =
        new MapSqlParameterSource().addValue("id", id.value()).addValue("rawText", rawText);
    jdbc.update(UPDATE_RAW_TEXT_SQL, params);
  }

  @Override
  @Transactional
  public void markFailed(ProcessingDocumentId id, String lastError) {
    assertOrgConsistent(id);
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("id", id.value())
            .addValue("currentStep", FAILED_STEP)
            .addValue("lastError", lastError);
    jdbc.update(UPDATE_FAILED_SQL, params);
  }

  private void assertOrgConsistent(ProcessingDocumentId id) {
    Map<String, Object> row;
    try {
      row =
          jdbc.queryForMap(
              SELECT_OWNER_SQL, new MapSqlParameterSource().addValue("id", id.value()));
    } catch (EmptyResultDataAccessException e) {
      throw new ProcessingDocumentNotFoundException(id);
    }
    String orgId = (String) row.get("organization_id");
    UUID storedDocId = (UUID) row.get("stored_document_id");
    StoredDocument parent =
        storedDocumentReader
            .get(StoredDocumentId.of(storedDocId))
            .orElseThrow(() -> new ProcessingDocumentNotFoundException(id));
    if (!Objects.equals(orgId, parent.organizationId())) {
      throw new ProcessingDocumentOrganizationMismatchException(id, orgId, parent.organizationId());
    }
  }
}
