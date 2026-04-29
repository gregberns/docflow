package com.docflow.api.sse;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.docflow.c3.events.ProcessingStepChanged;
import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class SsePublisherTest {

  private static final String ORG = "org-publisher-test";

  @Test
  void asyncRequestNotUsable_isSwallowed_andEmitterCompleted() throws Exception {
    AsyncRequestNotUsableException ex = new AsyncRequestNotUsableException("client gone");
    SseEmitter emitter = emitterThrowing(ex);
    SsePublisher publisher = publisherWith(emitter);

    publisher.onProcessingStepChanged(event());

    verify(emitter, times(1)).completeWithError(ex);
  }

  @Test
  void clientAbort_isSwallowed_andEmitterCompleted() throws Exception {
    ClientAbortException ex = new ClientAbortException(new IOException("broken pipe"));
    SseEmitter emitter = emitterThrowing(ex);
    SsePublisher publisher = publisherWith(emitter);

    publisher.onProcessingStepChanged(event());

    verify(emitter, times(1)).completeWithError(ex);
  }

  @Test
  void plainIoException_isStillSwallowed_andEmitterCompleted() throws Exception {
    IOException ex = new IOException("boom");
    SseEmitter emitter = emitterThrowing(ex);
    SsePublisher publisher = publisherWith(emitter);

    publisher.onProcessingStepChanged(event());

    verify(emitter, times(1)).completeWithError(ex);
  }

  private static SsePublisher publisherWith(SseEmitter emitter) {
    SseRegistry registry = mock(SseRegistry.class);
    when(registry.emittersFor(ORG)).thenReturn(Set.of(emitter));
    return new SsePublisher(registry);
  }

  private static SseEmitter emitterThrowing(Throwable ex) throws IOException {
    SseEmitter emitter = mock(SseEmitter.class);
    doThrow(ex).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
    return emitter;
  }

  private static ProcessingStepChanged event() {
    return new ProcessingStepChanged(
        UUID.randomUUID(),
        UUID.randomUUID(),
        ORG,
        "EXTRACTING",
        null,
        Instant.parse("2026-04-29T12:00:00Z"));
  }
}
