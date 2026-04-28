package com.docflow.config.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationDocTypeRepository
    extends JpaRepository<OrganizationDocTypeEntity, OrganizationDocTypeId> {

  List<OrganizationDocTypeEntity> findByOrganizationIdOrderByOrdinalAsc(String organizationId);
}
