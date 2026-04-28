package com.docflow.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StoredDocumentIdTest {

  @Test
  void generateProducesUuidV7() {
    StoredDocumentId id = StoredDocumentId.generate();
    assertThat(id.value().version()).isEqualTo(7);
  }

  @Test
  void sequentialIdsAreMonotonicallyIncreasing() {
    int count = 64;
    List<UUID> uuids = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      uuids.add(StoredDocumentId.generate().value());
    }
    for (int i = 1; i < uuids.size(); i++) {
      assertThat(uuids.get(i)).isGreaterThanOrEqualTo(uuids.get(i - 1));
    }
  }

  @Test
  void ofWrapsExistingUuid() {
    UUID raw = UUID.randomUUID();
    StoredDocumentId id = StoredDocumentId.of(raw);
    assertThat(id.value()).isEqualTo(raw);
    assertThat(id.toString()).isEqualTo(raw.toString());
  }
}
