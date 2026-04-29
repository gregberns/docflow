package com.docflow.config.catalog;

import java.util.List;
import java.util.Optional;

public interface DocumentTypeCatalog {

  Optional<DocumentTypeSchemaView> getDocumentTypeSchema(String orgId, String docTypeId);

  List<DocumentTypeSchemaView> listDocumentTypes(String orgId);
}
