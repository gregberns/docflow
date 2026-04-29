package com.docflow.api;

import com.docflow.api.sse.SseRegistry;
import java.io.IOException;
import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class SseController {

  private static final Duration RETRY = Duration.ofSeconds(5);

  private final SseRegistry registry;

  public SseController(SseRegistry registry) {
    this.registry = registry;
  }

  @GetMapping(
      value = "/api/organizations/{orgId}/stream",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(@PathVariable String orgId) throws IOException {
    SseEmitter emitter = new SseEmitter(0L);
    registry.register(orgId, emitter);
    emitter.send(SseEmitter.event().reconnectTime(RETRY.toMillis()).comment("ok"));
    return emitter;
  }
}
