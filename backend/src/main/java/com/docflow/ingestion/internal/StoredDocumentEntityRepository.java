package com.docflow.ingestion.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

interface StoredDocumentEntityRepository extends Repository<StoredDocumentEntity, UUID> {

  StoredDocumentEntity save(StoredDocumentEntity entity);

  StoredDocumentEntity saveAndFlush(StoredDocumentEntity entity);

  Optional<StoredDocumentEntity> findById(UUID id);
}
