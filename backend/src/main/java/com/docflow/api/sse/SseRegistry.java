package com.docflow.api.sse;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class SseRegistry {

  private final ConcurrentMap<String, Set<SseEmitter>> byOrg = new ConcurrentHashMap<>();

  public void register(String organizationId, SseEmitter emitter) {
    Set<SseEmitter> emitters =
        byOrg.computeIfAbsent(
            organizationId, k -> Collections.newSetFromMap(new ConcurrentHashMap<>()));
    emitters.add(emitter);
    Runnable remove = () -> remove(organizationId, emitter);
    emitter.onCompletion(remove);
    emitter.onTimeout(remove);
    emitter.onError(t -> remove.run());
  }

  public Set<SseEmitter> emittersFor(String organizationId) {
    Set<SseEmitter> emitters = byOrg.get(organizationId);
    return emitters == null ? Set.of() : emitters;
  }

  private void remove(String organizationId, SseEmitter emitter) {
    Set<SseEmitter> emitters = byOrg.get(organizationId);
    if (emitters == null) {
      return;
    }
    emitters.remove(emitter);
    if (emitters.isEmpty()) {
      byOrg.remove(organizationId, emitters);
    }
  }
}
