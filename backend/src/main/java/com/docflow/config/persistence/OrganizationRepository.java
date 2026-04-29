package com.docflow.config.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationRepository extends JpaRepository<OrganizationEntity, String> {

  List<OrganizationEntity> findAllByOrderByOrdinalAsc();
}
