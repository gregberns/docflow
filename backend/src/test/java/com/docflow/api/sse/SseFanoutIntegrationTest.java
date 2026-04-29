package com.docflow.api.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.docflow.api.SseController;
import com.docflow.c3.events.ExtractionCompleted;
import com.docflow.c3.events.ExtractionFailed;
import com.docflow.c3.events.ProcessingCompleted;
import com.docflow.c3.events.ProcessingStepChanged;
import com.docflow.workflow.events.DocumentStateChanged;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class SseFanoutIntegrationTest {

  private static final String ORG_A = "org-alpha";
  private static final String ORG_B = "org-beta";

  private SseRegistry registry;
  private SsePublisher publisher;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    registry = new SseRegistry();
    publisher = new SsePublisher(registry);
    mockMvc = standaloneSetup(new SseController(registry)).build();
  }

  @Test
  void register_emitsRetryFrameAndTextEventStreamContentType() throws Exception {
    MvcResult mvc =
        mockMvc
            .perform(get("/api/organizations/{orgId}/stream", ORG_A))
            .andExpect(request().asyncStarted())
            .andReturn();

    String body = mvc.getResponse().getContentAsString();
    assertThat(body).contains("retry:5000");
    assertThat(mvc.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
  }

  @Test
  void publish_processingStepChanged_reachesOnlySameOrgEmitters() throws Exception {
    RecordingEmitter emitterA1 = registerVia(ORG_A);
    RecordingEmitter emitterA2 = registerVia(ORG_A);
    RecordingEmitter emitterB = registerVia(ORG_B);

    ProcessingStepChanged event =
        new ProcessingStepChanged(
            UUID.randomUUID(),
            UUID.randomUUID(),
            ORG_A,
            "EXTRACTING",
            null,
            Instant.parse("2026-04-29T12:00:00Z"));

    publisher.onProcessingStepChanged(event);

    assertThat(emitterA1.frames).hasSize(1);
    assertThat(emitterA2.frames).hasSize(1);
    assertThat(emitterB.frames).isEmpty();

    SseEmitter.SseEventBuilder onlyA1 = emitterA1.frames.get(0);
    String rendered = stringify(onlyA1);
    assertThat(rendered).contains("event:ProcessingStepChanged");
    assertThat(rendered).contains("data:");
    assertThat(rendered).contains("\"organizationId\":\"" + ORG_A + "\"");
    assertThat(rendered).contains("\"currentStep\":\"EXTRACTING\"");
  }

  @Test
  void publish_documentStateChanged_isFannedOut() throws Exception {
    RecordingEmitter emitter = registerVia(ORG_A);

    DocumentStateChanged event =
        new DocumentStateChanged(
            UUID.randomUUID(),
            UUID.randomUUID(),
            ORG_A,
            "review",
            "AWAITING_REVIEW",
            "NONE",
            "submit",
            null,
            Instant.parse("2026-04-29T12:00:01Z"));

    publisher.onDocumentStateChanged(event);

    assertThat(emitter.frames).hasSize(1);
    assertThat(stringify(emitter.frames.get(0))).contains("event:DocumentStateChanged");
  }

  @Test
  void publish_nonEligibleEvents_areNeverEmitted() throws Exception {
    RecordingEmitter emitter = registerVia(ORG_A);

    publisher.onProcessingStepChanged(
        new ProcessingStepChanged(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "different-org",
            "EXTRACTING",
            null,
            Instant.now()));

    assertThat(emitter.frames).isEmpty();

    ProcessingCompleted completed =
        new ProcessingCompleted(
            UUID.randomUUID(), UUID.randomUUID(), ORG_A, "invoice", Map.of(), "raw", Instant.now());
    ExtractionCompleted extracted =
        new ExtractionCompleted(UUID.randomUUID(), ORG_A, Map.of(), "invoice", Instant.now());
    ExtractionFailed failed = new ExtractionFailed(UUID.randomUUID(), ORG_A, "boom", Instant.now());

    assertThat(publisherHandles(completed)).isFalse();
    assertThat(publisherHandles(extracted)).isFalse();
    assertThat(publisherHandles(failed)).isFalse();
    assertThat(emitter.frames).isEmpty();
  }

  @Test
  void publish_assignsMonotonicIds() throws Exception {
    RecordingEmitter emitter = registerVia(ORG_A);

    publisher.onProcessingStepChanged(
        new ProcessingStepChanged(
            UUID.randomUUID(), UUID.randomUUID(), ORG_A, "EXTRACTING", null, Instant.now()));
    publisher.onDocumentStateChanged(
        new DocumentStateChanged(
            UUID.randomUUID(),
            UUID.randomUUID(),
            ORG_A,
            "review",
            "AWAITING_REVIEW",
            "NONE",
            "submit",
            null,
            Instant.now()));
    publisher.onProcessingStepChanged(
        new ProcessingStepChanged(
            UUID.randomUUID(), UUID.randomUUID(), ORG_A, "PERSISTING", null, Instant.now()));

    assertThat(emitter.frames).hasSize(3);
    long id1 = parseId(emitter.frames.get(0));
    long id2 = parseId(emitter.frames.get(1));
    long id3 = parseId(emitter.frames.get(2));
    assertThat(id2).isGreaterThan(id1);
    assertThat(id3).isGreaterThan(id2);
  }

  @Test
  void completedEmitter_isRemovedFromRegistry() {
    RecordingEmitter emitter = new RecordingEmitter();
    registry.register(ORG_A, emitter);
    assertThat(registry.emittersFor(ORG_A)).contains(emitter);

    emitter.fireCompletion();

    assertThat(registry.emittersFor(ORG_A)).doesNotContain(emitter);
  }

  private RecordingEmitter registerVia(String orgId) throws Exception {
    RecordingEmitter emitter = new RecordingEmitter();
    registry.register(orgId, emitter);
    return emitter;
  }

  private boolean publisherHandles(Object event) {
    for (var method : SsePublisher.class.getDeclaredMethods()) {
      if (method.getParameterCount() != 1) {
        continue;
      }
      if (method.getParameterTypes()[0].isInstance(event)
          && method.isAnnotationPresent(org.springframework.context.event.EventListener.class)) {
        return true;
      }
    }
    return false;
  }

  private static long parseId(SseEmitter.SseEventBuilder builder) {
    String text = stringify(builder);
    int start = text.indexOf("id:");
    int end = text.indexOf('\n', start);
    return Long.parseLong(text.substring(start + 3, end).trim());
  }

  private static String stringify(SseEmitter.SseEventBuilder builder) {
    StringBuilder buf = new StringBuilder();
    for (var fragment : builder.build()) {
      Object data = fragment.getData();
      if (data != null) {
        buf.append(data);
      }
    }
    return buf.toString();
  }

  /**
   * Captures every {@link SseEmitter#send(SseEmitter.SseEventBuilder)} call without actually
   * routing through a servlet response, so unit assertions can inspect outgoing frame structure.
   * Also surfaces the registered onCompletion runnable so tests can drive lifecycle events
   * deterministically without a servlet container.
   */
  static final class RecordingEmitter extends SseEmitter {
    final List<SseEventBuilder> frames = new ArrayList<>();
    private Runnable onCompletion;

    RecordingEmitter() {
      super(0L);
    }

    @Override
    public void send(SseEventBuilder builder) throws IOException {
      frames.add(builder);
    }

    @Override
    public void send(Object object) throws IOException {
      // ignore: SsePublisher always uses the SseEventBuilder overload.
    }

    @Override
    public void onCompletion(Runnable callback) {
      this.onCompletion = callback;
      super.onCompletion(callback);
    }

    void fireCompletion() {
      if (onCompletion != null) {
        onCompletion.run();
      }
    }
  }
}
