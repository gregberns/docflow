package com.docflow.config.catalog;

import java.util.List;

public record OrganizationView(
    String id, String displayName, String iconId, List<String> documentTypeIds) {

  public OrganizationView {
    documentTypeIds = List.copyOf(documentTypeIds);
  }
}
