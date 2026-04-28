package com.docflow.config.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentTypeRepository extends JpaRepository<DocumentTypeEntity, DocumentTypeId> {

  List<DocumentTypeEntity> findByOrganizationId(String organizationId);
}
