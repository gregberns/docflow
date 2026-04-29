package com.docflow.scenario;

import com.docflow.c3.events.ProcessingStepChanged;
import com.docflow.workflow.events.DocumentStateChanged;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;

public final class ScenarioRecorder {

  private final Map<UUID, List<Object>> byDocumentId = new ConcurrentHashMap<>();
  private final List<ProcessingStepChanged> processingSteps = new ArrayList<>();

  @EventListener
  public synchronized void onDocumentStateChanged(DocumentStateChanged event) {
    byDocumentId.computeIfAbsent(event.documentId(), id -> new ArrayList<>()).add(event);
  }

  @EventListener
  public synchronized void onProcessingStepChanged(ProcessingStepChanged event) {
    processingSteps.add(event);
  }

  public synchronized List<Object> eventsFor(UUID documentId) {
    List<Object> events = byDocumentId.get(documentId);
    return events == null ? List.of() : List.copyOf(events);
  }

  public synchronized List<ProcessingStepChanged> processingSteps() {
    return List.copyOf(processingSteps);
  }

  public synchronized Map<UUID, List<Object>> snapshot() {
    Map<UUID, List<Object>> copy = new HashMap<>();
    byDocumentId.forEach((id, list) -> copy.put(id, List.copyOf(list)));
    return Map.copyOf(copy);
  }

  public synchronized void reset() {
    byDocumentId.clear();
    processingSteps.clear();
  }
}
