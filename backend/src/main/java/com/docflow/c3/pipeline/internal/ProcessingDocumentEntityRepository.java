package com.docflow.c3.pipeline.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

interface ProcessingDocumentEntityRepository extends Repository<ProcessingDocumentEntity, UUID> {

  ProcessingDocumentEntity save(ProcessingDocumentEntity entity);

  Optional<ProcessingDocumentEntity> findById(UUID id);
}
