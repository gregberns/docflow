package com.docflow.config.catalog;

import java.util.List;

public record DocumentTypeSchemaView(
    String organizationId, String id, String displayName, List<FieldView> fields) {

  public DocumentTypeSchemaView {
    fields = List.copyOf(fields);
  }
}
