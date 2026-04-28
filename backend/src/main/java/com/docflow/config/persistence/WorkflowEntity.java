package com.docflow.config.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@Table(name = "workflows")
@IdClass(WorkflowId.class)
public class WorkflowEntity {

  @Id
  @Column(name = "organization_id", nullable = false)
  private String organizationId;

  @Id
  @Column(name = "document_type_id", nullable = false)
  private String documentTypeId;

  protected WorkflowEntity() {}

  public WorkflowEntity(String organizationId, String documentTypeId) {
    this.organizationId = organizationId;
    this.documentTypeId = documentTypeId;
  }

  public String getOrganizationId() {
    return organizationId;
  }

  public String getDocumentTypeId() {
    return documentTypeId;
  }
}
