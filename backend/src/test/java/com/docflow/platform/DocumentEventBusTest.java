package com.docflow.platform;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.docflow.c3.events.ExtractionCompleted;
import com.docflow.c3.events.ExtractionFailed;
import com.docflow.c3.events.ProcessingCompleted;
import com.docflow.c3.events.ProcessingStepChanged;
import com.docflow.c3.events.StoredDocumentIngested;
import com.docflow.workflow.events.DocumentStateChanged;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@SpringBootTest(
    classes = {
      DocumentEventBusTest.TestApp.class,
      DocumentEventBus.class,
      AsyncConfig.class,
      DocumentEventBusTest.RecordingListener.class,
      DocumentEventBusTest.FailingListener.class
    },
    properties = {"spring.threads.virtual.enabled=true", "spring.main.web-application-type=none"})
class DocumentEventBusTest {

  private static final UUID ORG_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Autowired private DocumentEventBus bus;
  @Autowired private RecordingListener recording;
  @Autowired private FailingListener failing;

  private ListAppender<ILoggingEvent> handlerAppender;

  @BeforeEach
  void attachAppender() {
    handlerAppender = new ListAppender<>();
    handlerAppender.start();
    Logger asyncConfigLogger = (Logger) LoggerFactory.getLogger(AsyncConfig.class);
    asyncConfigLogger.addAppender(handlerAppender);
    asyncConfigLogger.setLevel(Level.ERROR);

    recording.reset();
    failing.reset();
  }

  @AfterEach
  void detachAppender() {
    Logger asyncConfigLogger = (Logger) LoggerFactory.getLogger(AsyncConfig.class);
    asyncConfigLogger.detachAppender(handlerAppender);
    handlerAppender.stop();
  }

  @Test
  void publishReturnsSynchronouslyAndListenerRunsOnVirtualThread() throws InterruptedException {
    StoredDocumentIngested event =
        new StoredDocumentIngested(UUID.randomUUID(), ORG_ID, UUID.randomUUID(), Instant.now());

    Thread publisherThread = Thread.currentThread();

    bus.publish(event);

    assertThat(Thread.currentThread())
        .as("publish() returns synchronously on the caller thread")
        .isSameAs(publisherThread);

    boolean delivered = recording.latch().await(5, TimeUnit.SECONDS);
    assertThat(delivered).as("async listener received event within 5s").isTrue();

    assertThat(recording.lastEvent()).isSameAs(event);
    assertThat(recording.listenerThreadVirtual())
        .as("listener executed on a virtual thread")
        .isTrue();
    assertThat(recording.listenerThread())
        .as("listener executed on a different thread than the publisher")
        .isNotSameAs(publisherThread);
  }

  @Test
  void asyncListenerExceptionIsLoggedAtError() {
    ProcessingStepChanged event =
        new ProcessingStepChanged(
            UUID.randomUUID(), UUID.randomUUID(), ORG_ID, "EXTRACTING", "boom-step", Instant.now());

    bus.publish(event);

    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> !handlerAppender.list.isEmpty());

    assertThat(handlerAppender.list)
        .as("AsyncUncaughtExceptionHandler must log at ERROR")
        .anySatisfy(
            entry -> {
              assertThat(entry.getLevel()).isEqualTo(Level.ERROR);
              assertThat(entry.getThrowableProxy()).isNotNull();
              assertThat(entry.getThrowableProxy().getMessage()).contains("boom-step");
            });
  }

  @Test
  void allSixConcreteEventsImplementDocumentEvent() {
    Instant now = Instant.now();
    UUID id = UUID.randomUUID();

    DocumentEvent[] events = {
      new StoredDocumentIngested(id, ORG_ID, id, now),
      new ProcessingStepChanged(id, id, ORG_ID, "TEXT_EXTRACTING", null, now),
      new ProcessingCompleted(id, id, ORG_ID, "invoice", Map.of("amount", "1.00"), "raw", now),
      new ExtractionCompleted(id, ORG_ID, Map.of("k", "v"), "invoice", now),
      new ExtractionFailed(id, ORG_ID, "schema-violation", now),
      new DocumentStateChanged(id, id, ORG_ID, "Review", "AWAITING_REVIEW", "NONE", null, null, now)
    };

    for (DocumentEvent e : events) {
      assertThat(e).isInstanceOf(DocumentEvent.class);
      assertThat(e.organizationId()).isEqualTo(ORG_ID);
      assertThat(e.occurredAt()).isEqualTo(now);
    }
  }

  @org.springframework.boot.autoconfigure.SpringBootApplication(
      exclude = {
        org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class,
        org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration.class,
        org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration.class
      })
  @Import({DocumentEventBus.class, AsyncConfig.class})
  static class TestApp {}

  @Component
  static class RecordingListener {
    private final AtomicReference<StoredDocumentIngested> last = new AtomicReference<>();
    private final AtomicReference<Thread> thread = new AtomicReference<>();
    private final AtomicBoolean virtual = new AtomicBoolean();
    private volatile CountDownLatch latch = new CountDownLatch(1);

    @Async
    @EventListener
    public void onIngested(StoredDocumentIngested event) {
      last.set(event);
      Thread current = Thread.currentThread();
      thread.set(current);
      virtual.set(current.isVirtual());
      latch.countDown();
    }

    void reset() {
      last.set(null);
      thread.set(null);
      virtual.set(false);
      latch = new CountDownLatch(1);
    }

    StoredDocumentIngested lastEvent() {
      return last.get();
    }

    Thread listenerThread() {
      return thread.get();
    }

    boolean listenerThreadVirtual() {
      return virtual.get();
    }

    CountDownLatch latch() {
      return latch;
    }
  }

  @Component
  static class FailingListener {
    @Async
    @EventListener
    public void onStepChanged(ProcessingStepChanged event) {
      throw new IllegalStateException(event.error());
    }

    void reset() {
      // no-op; preserved for symmetry with RecordingListener
    }
  }
}
