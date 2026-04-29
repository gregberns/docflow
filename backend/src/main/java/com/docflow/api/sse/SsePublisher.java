package com.docflow.api.sse;

import com.docflow.c3.events.ProcessingStepChanged;
import com.docflow.platform.DocumentEvent;
import com.docflow.workflow.events.DocumentStateChanged;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.catalina.connector.ClientAbortException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Component
public class SsePublisher {

  private static final Logger LOG = LoggerFactory.getLogger(SsePublisher.class);
  private static final AtomicLong SEQUENCE = new AtomicLong();

  private final SseRegistry registry;
  private final ObjectMapper jsonMapper = JsonMapper.builder().build();

  public SsePublisher(SseRegistry registry) {
    this.registry = registry;
  }

  @Async
  @EventListener
  public void onProcessingStepChanged(ProcessingStepChanged event) {
    fanOut(event, ProcessingStepChanged.class.getSimpleName());
  }

  @Async
  @EventListener
  public void onDocumentStateChanged(DocumentStateChanged event) {
    fanOut(event, DocumentStateChanged.class.getSimpleName());
  }

  private void fanOut(DocumentEvent event, String eventName) {
    Set<SseEmitter> emitters = registry.emittersFor(event.organizationId());
    if (emitters.isEmpty()) {
      return;
    }
    String payload;
    try {
      payload = jsonMapper.writeValueAsString(event);
    } catch (RuntimeException ex) {
      LOG.warn("SSE: failed to serialize {}: {}", eventName, ex.toString());
      return;
    }
    long id = SEQUENCE.incrementAndGet();
    for (SseEmitter emitter : emitters) {
      try {
        emitter.send(SseEmitter.event().id(Long.toString(id)).name(eventName).data(payload));
      } catch (AsyncRequestNotUsableException | ClientAbortException ex) {
        LOG.debug("SSE: client gone, dropping emitter ({})", ex.getClass().getSimpleName());
        emitter.completeWithError(ex);
      } catch (IOException | IllegalStateException ex) {
        emitter.completeWithError(ex);
      }
    }
  }
}
