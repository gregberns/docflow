package com.docflow.ingestion.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "stored_documents")
class StoredDocumentEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "organization_id", nullable = false)
  private String organizationId;

  @Column(name = "uploaded_at", nullable = false)
  private Instant uploadedAt;

  @Column(name = "source_filename", nullable = false)
  private String sourceFilename;

  @Column(name = "mime_type", nullable = false)
  private String mimeType;

  @Column(name = "storage_path", nullable = false)
  private String storagePath;

  protected StoredDocumentEntity() {}

  StoredDocumentEntity(
      UUID id,
      String organizationId,
      Instant uploadedAt,
      String sourceFilename,
      String mimeType,
      String storagePath) {
    this.id = id;
    this.organizationId = organizationId;
    this.uploadedAt = uploadedAt;
    this.sourceFilename = sourceFilename;
    this.mimeType = mimeType;
    this.storagePath = storagePath;
  }

  UUID getId() {
    return id;
  }

  String getOrganizationId() {
    return organizationId;
  }

  Instant getUploadedAt() {
    return uploadedAt;
  }

  String getSourceFilename() {
    return sourceFilename;
  }

  String getMimeType() {
    return mimeType;
  }

  String getStoragePath() {
    return storagePath;
  }
}
