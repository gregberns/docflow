package com.docflow.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.docflow.workflow.events.DocumentStateChanged;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class DocumentStateChangedShapeTest {

  private static final Set<String> DOCUMENTED_KEYS =
      Set.of(
          "documentId",
          "storedDocumentId",
          "organizationId",
          "currentStage",
          "currentStatus",
          "reextractionStatus",
          "action",
          "comment",
          "occurredAt");

  private final ObjectMapper mapper = JsonMapper.builder().build();

  @Test
  void serializedJsonContainsOnlyTheDocumentedKeys() throws Exception {
    DocumentStateChanged event =
        new DocumentStateChanged(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "test-org",
            "stage-review",
            "AWAITING_REVIEW",
            "NONE",
            "APPROVE",
            "looks good",
            Instant.parse("2026-04-29T10:00:00Z"));

    String json = mapper.writeValueAsString(event);
    Map<String, Object> deserialized =
        mapper.readValue(json, new TypeReference<Map<String, Object>>() {});

    assertThat(deserialized.keySet()).isEqualTo(DOCUMENTED_KEYS);
  }

  @Test
  void serializedJsonDoesNotContainPreviousStageOrPreviousStatus() throws Exception {
    DocumentStateChanged event =
        new DocumentStateChanged(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "test-org",
            "stage-pm-approval",
            "AWAITING_APPROVAL",
            "NONE",
            "APPROVE",
            null,
            Instant.parse("2026-04-29T10:00:00Z"));

    String json = mapper.writeValueAsString(event);
    Map<String, Object> deserialized =
        mapper.readValue(json, new TypeReference<Map<String, Object>>() {});

    assertThat(deserialized).doesNotContainKeys("previousStage", "previousStatus");
    assertThat(json).doesNotContain("previousStage").doesNotContain("previousStatus");
  }

  @Test
  void recordComponentsMatchTheDocumentedShape() {
    Set<String> componentNames =
        java.util.Arrays.stream(DocumentStateChanged.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));

    assertThat(componentNames).isEqualTo(DOCUMENTED_KEYS);
    assertThat(componentNames).doesNotContain("previousStage", "previousStatus");
  }

  @Test
  void nullActionAndCommentSerializeToNullNotMissing() throws Exception {
    DocumentStateChanged event =
        new DocumentStateChanged(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "test-org",
            "stage-review",
            "AWAITING_REVIEW",
            "NONE",
            null,
            null,
            Instant.parse("2026-04-29T10:00:00Z"));

    String json = mapper.writeValueAsString(event);
    Map<String, Object> deserialized =
        mapper.readValue(json, new TypeReference<Map<String, Object>>() {});

    assertThat(deserialized.keySet()).isEqualTo(DOCUMENTED_KEYS);
    assertThat(deserialized).containsEntry("action", null).containsEntry("comment", null);
  }
}
