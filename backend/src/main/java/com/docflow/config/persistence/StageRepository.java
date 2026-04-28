package com.docflow.config.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StageRepository extends JpaRepository<StageEntity, StageId> {

  List<StageEntity> findByOrganizationIdAndDocumentTypeIdOrderByOrdinalAsc(
      String organizationId, String documentTypeId);
}
