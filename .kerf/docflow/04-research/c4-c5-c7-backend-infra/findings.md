# C4 / C5 / C7 — Backend Infrastructure Research

**Post-research revision (component walkthrough with user).** Document lifecycle restructured into three entities: `StoredDocument` (C2), `ProcessingDocument` (C3, transient), `Document` (C4, processed). Workflow now starts at Review (Upload/Classify/Extract are processing pipeline steps in C3, not workflow stages). The infrastructure recommendations below (event bus, virtual threads, SSE, error handling, AppConfig, grep task, property-based testing, Stop hook) all still apply unchanged. SSE event vocabulary expands to include processing events alongside workflow events; C4 subscribes to C3's `ProcessingCompleted` event to materialize the `Document` aggregate.

Pass 4 research for infrastructure seams across the Workflow Engine (C4), API + Real-Time layer (C5), and Platform & Quality (C7). Stack locked: Java 25, Spring Boot 4.0 (Spring Framework 7), Jakarta EE 11 / Servlet 6.1, Jackson 3 (`tools.jackson.*`), Gradle 9, PostgreSQL 17+, Flyway, Anthropic Java SDK.

---

## 1. `DocumentEventBus` implementation

| Option | Pros | Cons |
|---|---|---|
| **(a) `ApplicationEventPublisher` + `@EventListener`** | Zero extra dep; DI-native; `@Async` opts listeners off-thread; test support via `@RecordApplicationEvents`; inheritance-aware dispatch | `publishEvent` blocks on each non-`@Async` listener |
| (b) Reactor `Sinks.Many<T>` | Multi-subscriber with backpressure | Drags Reactor onto an MVC app; SSE here is MVC `SseEmitter` |
| (c) Guava `EventBus` | Tiny API | External dep; no Spring lifecycle integration |
| (d) Hand-rolled | Zero magic | Re-implements what Spring ships |

### Recommendation: **(a) `ApplicationEventPublisher` + `@EventListener`, with `@Async` on listeners that must not block the publisher.**

Pair with `spring.threads.virtual.enabled=true` so `SimpleAsyncTaskExecutor` (which `@Async` uses when no named `TaskExecutor` bean exists) runs listeners on virtual threads.

### Sketch

```java
public sealed interface DocumentEvent
    permits ClassificationCompleted, ClassificationFailed,
            ExtractionCompleted, ExtractionFailed,
            ProcessingStepChanged, ProcessingFailed, ProcessingCompleted,
            DocumentStateChanged,
            DocumentReextractionStarted, DocumentReextractionCompleted, DocumentReextractionFailed {
  UUID documentId();
  String organizationId();   // every event carries org so SSE can filter cheaply
  Instant at();
}

public record DocumentStateChanged(
    UUID documentId, String organizationId,
    String previousStage, String currentStage,
    String action, String comment, Instant at) implements DocumentEvent {}

@Component
public class DocumentEventBus {
  private final ApplicationEventPublisher publisher;
  public DocumentEventBus(ApplicationEventPublisher p) { this.publisher = p; }
  public void publish(DocumentEvent e) { publisher.publishEvent(e); }
}

@Component
public class SseFanOut {
  @Async @EventListener
  public void onEvent(DocumentEvent e) {
    registry.emittersFor(e.organizationId()).forEach(em -> em.trySend(e));
  }
}
```

`@EnableAsync` on the main class. `@RecordApplicationEvents` in tests gives assertion-grade visibility.

ProcessingDocument-related events (`ProcessingStepChanged`, `ProcessingFailed`, `ProcessingCompleted`) carry `processingDocumentId` and `storedDocumentId`; Document-related events (`DocumentStateChanged`, `DocumentReextractionStarted/Completed/Failed`) carry `documentId`. All carry `organizationId` for SSE filtering. The `documentId()` accessor on the sealed interface is therefore nullable for processing-only events, or the interface can be split into `ProcessingEvent` / `DocumentEvent` sub-hierarchies during implementation.

---

## 2. Async execution for classify/extract

### Recommendation: **Java 25 virtual threads, globally enabled via `spring.threads.virtual.enabled=true`, with `@Async` on C3's `LlmClassifier.classify` / `LlmExtractor.extract`.**

Spring Boot 4 specifics:
- Property is **opt-in**, not default-on. Boot 4.0 does not flip it automatically.
- If a named `TaskExecutor` bean exists, it overrides the virtual-thread default for `@Async`. Keep the default.
- **Pinning risk.** The Anthropic SDK uses Apache HttpClient; verify no `synchronized` wraps the I/O hot path. Smoke-test with ~50 concurrent classify calls during implementation.
- For test determinism, entry points return `CompletableFuture<Void>` so tests can `.join()`. Public C4 call remains fire-and-forget.

```java
@Service
class LlmClassifier {
  @Async
  public CompletableFuture<Void> classify(UUID documentId) {
    try {
      var result = anthropic.classify(...);
      documentWriter.recordClassification(documentId, result.type());
      eventBus.publish(new ClassificationCompleted(documentId, result.type(), ...));
    } catch (Exception e) {
      documentWriter.recordClassificationFailure(documentId, e.getMessage());
      eventBus.publish(new ClassificationFailed(documentId, ErrorCode.LLM_UNAVAILABLE, ...));
    }
    return CompletableFuture.completedFuture(null);
  }
}
```

---

## 3. SSE in Spring Boot 4

### Recommendation: **`SseEmitter` (MVC)** — matches the problem-space commitment. WebFlux isn't justified at this scale.

Spring Boot 4 / Jakarta EE 11 implications:
- Servlet 6.1 async context fully supported; Tomcat 11 (bundled) fires `onError`/`onTimeout` on client disconnect.
- `spring.mvc.async.request-timeout` applies to `SseEmitter` unless constructor timeout is passed. For long-lived dashboards use `new SseEmitter(0L)` — default 30s will close mid-session.
- With virtual threads on, the Tomcat request thread writing the SSE response is virtual — blocking writes park cheaply.

### Per-org fan-out

```java
@Component
class SseRegistry {
  private final Map<String, Set<SseEmitter>> byOrg = new ConcurrentHashMap<>();

  SseEmitter register(String orgId) {
    var emitter = new SseEmitter(0L);
    byOrg.computeIfAbsent(orgId, k -> ConcurrentHashMap.newKeySet()).add(emitter);
    Runnable cleanup = () -> byOrg.getOrDefault(orgId, Set.of()).remove(emitter);
    emitter.onCompletion(cleanup);
    emitter.onTimeout(() -> { cleanup.run(); emitter.complete(); });
    emitter.onError(t -> cleanup.run());
    return emitter;
  }

  Set<SseEmitter> emittersFor(String orgId) {
    return byOrg.getOrDefault(orgId, Set.of());
  }
}

@RestController
class SseController {
  @GetMapping(path = "/api/organizations/{orgId}/stream",
              produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  SseEmitter stream(@PathVariable String orgId) {
    var em = registry.register(orgId);
    try { em.send(SseEmitter.event().comment("retry").reconnectTime(5000)); }
    catch (IOException ignored) {}
    return em;
  }
}

@Component
class SsePublisher {
  private final AtomicLong seq = new AtomicLong();

  @Async @EventListener
  public void onEvent(DocumentEvent e) {
    var id = String.valueOf(seq.incrementAndGet());
    var frame = SseEmitter.event().id(id).name(e.getClass().getSimpleName()).data(e);
    for (var em : registry.emittersFor(e.organizationId())) {
      try { em.send(frame); }
      catch (Exception ex) { em.completeWithError(ex); }
    }
  }
}
```

Every `DocumentEvent` carries `organizationId`, so per-org scoping is O(subscribers of that org).

All event types listed in C5-R8 of `03-components.md` flow through this same fan-out mechanism — single per-org stream, typed events.

---

## 4. Error handling framework

### Recommendation: **`ProblemDetail` (RFC 7807) with custom properties.**

Spring Framework 6+ supports `ProblemDetail.setProperty(key, value)` which serializes into the top-level JSON. RFC 7807 fields (`type`, `title`, `status`, `detail`) travel alongside `code` / `message` / `details`.

Mapping for C5-R9a's enumerated codes:
- `code` → custom top-level property.
- `message` → `detail` and also custom `message`.
- `details` → custom property, array of `{ path, message }`.
- `type` → `https://docflow.dev/errors/{code}`.

```java
public enum ErrorCode {
  UNKNOWN_ORGANIZATION(404), UNKNOWN_DOCUMENT(404), UNKNOWN_DOC_TYPE(404),
  UNSUPPORTED_MEDIA_TYPE(415), INVALID_FILE(400),
  VALIDATION_FAILED(400), INVALID_ACTION(409), EXTRACTION_IN_PROGRESS(409),
  LLM_UNAVAILABLE(502), INTERNAL_ERROR(500);
  public final int status;
  ErrorCode(int s) { this.status = s; }
  public URI typeUri() { return URI.create("https://docflow.dev/errors/" + name()); }
}

@RestControllerAdvice
class GlobalExceptionHandler {
  @ExceptionHandler(DocflowException.class)
  ProblemDetail handle(DocflowException ex) {
    var pd = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(ex.code.status), ex.getMessage());
    pd.setType(ex.code.typeUri());
    pd.setTitle(ex.code.name());
    pd.setProperty("code", ex.code.name());
    pd.setProperty("message", ex.getMessage());
    if (ex.details != null && !ex.details.isEmpty()) pd.setProperty("details", ex.details);
    return pd;
  }
}
```

Contract test asserts `code`, `message`, `details` are top-level keys for each `ErrorCode`.

Possible addition for the post-research entity split: `PROCESSING_FAILED` (502) for surfacing C3 pipeline failures via the API when a `ProcessingDocument` errors out before a `Document` is materialized.

---

## 5. AppConfig binding (C7-R13)

### Recommendation: **`@ConfigurationProperties` on records + `@Validated` + Jakarta Bean Validation.**

Boot binds env vars / YAML into records at startup; with `@Validated`, a `BindValidationException` fires before `ApplicationContext` refresh completes — the app fails to start, not mid-request.

```java
@ConfigurationProperties("docflow")
@Validated
public record AppConfig(
    @Valid Llm llm,
    @Valid Database database,
    @Valid Storage storage) {

  public record Llm(
      @NotBlank String modelId,
      @NotBlank String apiKey,
      @Min(1) int retryAttempts) {}

  public record Database(
      @NotBlank String url, @NotBlank String username, @NotBlank String password) {}

  public record Storage(@NotBlank String rootPath) {}
}
```

```yaml
docflow:
  llm:
    model-id: claude-sonnet-4-6
    api-key: ${ANTHROPIC_API_KEY}
    retry-attempts: 2
  database:
    url: ${DB_URL}
    username: ${DB_USER}
    password: ${DB_PASSWORD}
  storage:
    root-path: ${STORAGE_ROOT:/var/lib/docflow/files}
```

Enable with `@EnableConfigurationProperties(AppConfig.class)`. Inject `AppConfig` wherever needed. C7-R5's `grepForbiddenStrings` forbids `System.getenv` / `@Value` outside the config package.

Startup-fail test:

```java
@SpringBootTest(properties = "docflow.llm.api-key=")
class MissingApiKeyStartupTest {
  @Test void startupFails() {
    assertThatThrownBy(() -> SpringApplication.run(App.class))
        .hasRootCauseInstanceOf(ConstraintViolationException.class);
  }
}
```

---

## 6. `grepForbiddenStrings` Gradle task

### Recommendation: **Hand-rolled Gradle task, Kotlin DSL.**

`forbiddenPatterns` plugin is dormant. Checkstyle's `RegexpSingleline` works but buries rules in formatting config. A ~30-line task hits the requirement without compromise.

```kotlin
val forbiddenPatterns = listOf(
    Regex("""\"Manager Approval\""""), Regex("""\"Finance Approval\""""),
    Regex("""\"Attorney Approval\""""), Regex("""\"Billing Approval\""""),
    Regex("""\"Partner Approval\""""), Regex("""\"Project Manager Approval\""""),
    Regex("""\"Accounting Approval\""""), Regex("""\"Client Approval\""""),
    Regex("""(?i)\"pinnacle\""""), Regex("""(?i)\"riverside\""""), Regex("""(?i)\"ironworks\""""),
    Regex("""System\.getenv\("""), Regex("""@Value\(""")
)

val configPackagePath = "src/main/java/com/docflow/config"

tasks.register("grepForbiddenStrings") {
    group = "verification"
    val srcTree = fileTree("src/main/java") { include("**/*.java") }
    inputs.files(srcTree)
    doLast {
        val hits = mutableListOf<String>()
        srcTree.forEach { f ->
            val isConfig = f.absolutePath.replace('\\','/').contains(configPackagePath)
            f.readLines().forEachIndexed { i, line ->
                forbiddenPatterns.forEach { p ->
                    val envRead = p.pattern.contains("getenv") || p.pattern.contains("@Value")
                    if (p.containsMatchIn(line) && !(envRead && isConfig)) {
                        hits += "${f.relativeTo(projectDir)}:${i+1}: ${line.trim()}"
                    }
                }
            }
        }
        if (hits.isNotEmpty()) throw GradleException(
            "grepForbiddenStrings: ${hits.size} violation(s):\n${hits.joinToString("\n")}")
    }
}

tasks.named("check") { dependsOn("grepForbiddenStrings") }
```

Generating the stage list from C1 config at build time would eliminate drift — noted as a Pass 5 refinement.

---

## 7. Property-based testing library for C4

### Recommendation: **jqwik, latest 1.9.x.**

Standalone JUnit 5 *platform* engine (not a Jupiter extension), so it runs side-by-side with `junit-jupiter`. Fits C4-R9 perfectly.

**Compatibility notes:**
- `jqwik-spring` 0.12.x targets Spring 6.x / Boot 3.x; Boot 4 support **not yet confirmed upstream**.
- Mitigation: the workflow engine is a pure-Java state machine — C4-R9a explicitly makes `WorkflowCatalog` constructor-injected in tests. Property tests for C4 don't need Spring context at all.
- Java 25: jqwik 1.9.x targets Java 8+ bytecode; runs on Java 25.

```kotlin
dependencies {
    testImplementation("net.jqwik:jqwik:1.9.3")
    testImplementation("org.junit.jupiter:junit-jupiter")
}
tasks.test { useJUnitPlatform { includeEngines("jqwik", "junit-jupiter") } }
```

---

## 8. Stop hook for "done means green" (C7-R7)

### Recommendation: **A `Stop` hook that runs an aggregated fast-gate command.**

Long-running jobs (Playwright E2E, live eval) live in separate Gradle tasks that `check` does not depend on.

**Runs:** `./gradlew check` (Spotless, Checkstyle, PMD, Error Prone, JaCoCo 95%, `grepForbiddenStrings`, unit/property/HTTP-seam tests) + `npm --prefix frontend run check` (ESLint, Prettier, tsc, Vitest w/ coverage).

**Excluded:** Playwright E2E (separate `e2eTest` task), `--live` LLM eval.

```json
{
  "hooks": {
    "Stop": [
      {
        "matcher": "",
        "hooks": [
          {
            "type": "command",
            "command": "cd \"$CLAUDE_PROJECT_DIR\" && ./gradlew --quiet check && npm --prefix frontend run --silent check",
            "timeout": 600
          }
        ]
      }
    ]
  }
}
```

Non-zero exit blocks "done"; Claude Code surfaces failing output into the transcript.

---

## Risks & unknowns

1. **Virtual-thread pinning in the Anthropic SDK.** Smoke test ~50 concurrent classify calls during implementation. Fallback: named `ThreadPoolTaskExecutor` with ~20 platform threads.
2. **`jqwik-spring` not yet advertised for Boot 4.** Low risk — C4-R9a decouples engine tests from Spring DI.
3. **Checkstyle + Java 25 grammar.** `02-analysis.md` §4.3 flags Checkstyle as "unverified at exact minor." Pin version early, confirm.
4. **SSE behind reverse proxies.** nginx buffers `text/event-stream` by default. Out of scope for take-home (dev is same-origin). Note for README.
5. **`ProblemDetail` + Jackson 3.** Boot 4 uses Jackson 3. Third-party snippets using `com.fasterxml.jackson.*` won't port. Verify serialized shape against golden-file contract test.
6. **Stop-hook cadence on slow machines.** Full `./gradlew check` can creep past 60–90s. Measure; if problematic, split into `PreToolUse` (format + grep only) and `Stop` (full check).
7. **`@EventListener` swallowed exceptions.** Async listener exceptions go to `AsyncUncaughtExceptionHandler` — register one that logs at ERROR.

---

## Sources

- Spring Boot 4 reference: task execution and scheduling — https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html
- Baeldung: Returning Errors Using ProblemDetail — https://www.baeldung.com/spring-boot-return-errors-problemdetail
- Spring ProblemDetail + ErrorResponse — https://howtodoinjava.com/spring-mvc/spring-problemdetail-errorresponse/
- Baeldung: Server-Sent Events in Spring — https://www.baeldung.com/spring-server-sent-events
- Spring Framework 7 SseEmitter javadoc — https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/servlet/mvc/method/annotation/SseEmitter.html
- Claude Code hooks reference — https://code.claude.com/docs/en/hooks
- jqwik release notes — https://jqwik.net/release-notes.html
- Spring Boot 4.0 available now — https://spring.io/blog/2025/11/20/spring-boot-4-0-0-available-now/
