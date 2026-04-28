package com.docflow.config.persistence;

import java.io.Serializable;
import java.util.Objects;

public class WorkflowId implements Serializable {

  private static final long serialVersionUID = 1L;

  private String organizationId;
  private String documentTypeId;

  public WorkflowId() {}

  public WorkflowId(String organizationId, String documentTypeId) {
    this.organizationId = organizationId;
    this.documentTypeId = documentTypeId;
  }

  public String getOrganizationId() {
    return organizationId;
  }

  public void setOrganizationId(String organizationId) {
    this.organizationId = organizationId;
  }

  public String getDocumentTypeId() {
    return documentTypeId;
  }

  public void setDocumentTypeId(String documentTypeId) {
    this.documentTypeId = documentTypeId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof WorkflowId other)) {
      return false;
    }
    return Objects.equals(organizationId, other.organizationId)
        && Objects.equals(documentTypeId, other.documentTypeId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(organizationId, documentTypeId);
  }
}
