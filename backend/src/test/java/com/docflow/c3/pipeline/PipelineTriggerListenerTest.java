package com.docflow.c3.pipeline;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.docflow.c3.events.StoredDocumentIngested;
import com.docflow.ingestion.StoredDocumentId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PipelineTriggerListenerTest {

  @Test
  void onIngestedCallsStartExactlyOnceWithEventPayload() {
    ProcessingDocumentService service = mock(ProcessingDocumentService.class);
    PipelineTriggerListener listener = new PipelineTriggerListener(service);

    UUID storedId = UUID.randomUUID();
    UUID procId = UUID.randomUUID();
    StoredDocumentIngested event =
        new StoredDocumentIngested(
            storedId, "riverside-bistro", procId, Instant.parse("2026-04-28T12:00:00Z"));

    listener.onIngested(event);

    ArgumentCaptor<StoredDocumentId> storedCaptor = ArgumentCaptor.forClass(StoredDocumentId.class);
    ArgumentCaptor<ProcessingDocumentId> procCaptor =
        ArgumentCaptor.forClass(ProcessingDocumentId.class);
    verify(service, times(1)).start(storedCaptor.capture(), procCaptor.capture());
    org.assertj.core.api.Assertions.assertThat(storedCaptor.getValue().value()).isEqualTo(storedId);
    org.assertj.core.api.Assertions.assertThat(procCaptor.getValue().value()).isEqualTo(procId);

    verify(service, times(1)).start(any(), any());
  }
}
