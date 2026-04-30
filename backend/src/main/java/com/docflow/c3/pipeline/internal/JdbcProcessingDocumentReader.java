package com.docflow.c3.pipeline.internal;

import com.docflow.c3.pipeline.ProcessingDocument;
import com.docflow.c3.pipeline.ProcessingDocumentId;
import com.docflow.c3.pipeline.ProcessingDocumentReader;
import com.docflow.ingestion.StoredDocumentId;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.stereotype.Component;

@Component
class JdbcProcessingDocumentReader implements ProcessingDocumentReader {

  private static final String SELECT_SQL =
      "SELECT id, stored_document_id, organization_id, current_step, raw_text, last_error, "
          + "created_at FROM processing_documents WHERE id = :id";

  private final NamedParameterJdbcOperations jdbc;

  JdbcProcessingDocumentReader(NamedParameterJdbcOperations jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<ProcessingDocument> get(ProcessingDocumentId id) {
    try {
      ProcessingDocument doc =
          jdbc.queryForObject(SELECT_SQL, Map.of("id", id.value()), rowMapper());
      return Optional.ofNullable(doc);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  private static RowMapper<ProcessingDocument> rowMapper() {
    return (ResultSet rs, int rowNum) -> {
      Timestamp createdAt = rs.getTimestamp("created_at");
      return new ProcessingDocument(
          ProcessingDocumentId.of(rs.getObject("id", UUID.class)),
          StoredDocumentId.of(rs.getObject("stored_document_id", UUID.class)),
          rs.getString("organization_id"),
          rs.getString("current_step"),
          rs.getString("raw_text"),
          rs.getString("last_error"),
          createdAt == null ? null : createdAt.toInstant());
    };
  }
}
