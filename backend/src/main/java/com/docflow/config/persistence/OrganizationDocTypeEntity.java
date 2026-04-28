package com.docflow.config.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@Table(name = "organization_doc_types")
@IdClass(OrganizationDocTypeId.class)
public class OrganizationDocTypeEntity {

  @Id
  @Column(name = "organization_id", nullable = false)
  private String organizationId;

  @Id
  @Column(name = "document_type_id", nullable = false)
  private String documentTypeId;

  @Column(name = "ordinal", nullable = false)
  private int ordinal;

  protected OrganizationDocTypeEntity() {}

  public OrganizationDocTypeEntity(String organizationId, String documentTypeId, int ordinal) {
    this.organizationId = organizationId;
    this.documentTypeId = documentTypeId;
    this.ordinal = ordinal;
  }

  public String getOrganizationId() {
    return organizationId;
  }

  public String getDocumentTypeId() {
    return documentTypeId;
  }

  public int getOrdinal() {
    return ordinal;
  }
}
