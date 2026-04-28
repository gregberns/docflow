package com.docflow.config.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransitionRepository extends JpaRepository<TransitionEntity, UUID> {

  List<TransitionEntity> findByOrganizationIdAndDocumentTypeIdOrderByOrdinalAsc(
      String organizationId, String documentTypeId);
}
