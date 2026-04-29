package com.docflow.config.catalog;

import java.util.List;
import java.util.Optional;

public interface OrganizationCatalog {

  Optional<OrganizationView> getOrganization(String orgId);

  List<OrganizationView> listOrganizations();

  List<String> getAllowedDocTypes(String orgId);
}
