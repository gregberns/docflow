package com.docflow.scenario;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

public final class ScenarioSseClient implements AutoCloseable {

  private final HttpClient client;
  private final List<String> frames = new CopyOnWriteArrayList<>();
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private CompletableFuture<Void> pump;

  public ScenarioSseClient() {
    this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }

  public void open(String url) {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "text/event-stream")
            .timeout(Duration.ofSeconds(60))
            .GET()
            .build();

    this.pump =
        client
            .sendAsync(request, BodyHandlers.ofLines())
            .thenAccept(
                response -> {
                  try (Stream<String> lines = response.body()) {
                    lines.takeWhile(line -> !closed.get()).forEach(frames::add);
                  }
                });
  }

  public List<String> frames() {
    return List.copyOf(frames);
  }

  @Override
  public void close() throws IOException {
    closed.set(true);
    if (pump != null) {
      pump.cancel(true);
    }
  }
}
