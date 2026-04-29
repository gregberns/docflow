# Research — C-EVAL-4 (Scenario-test framework)

Most architecture decisions are made. This pass resolves the remaining open questions.

## Settled decisions (recap from problem space and analysis)

1. **Stub seam: `LlmClassifier` and `LlmExtractor`.** Stable Java interface; not coupled to message-shape changes. Lower seam (`LlmCallExecutor`) rejected — see below.
2. **Real PDFBox.** PDFBox quality is verified clean (C-EVAL-1). No fixtures need to fake the text-extraction path.
3. **Real Postgres via Testcontainers, real Flyway.** Same migrations as production; no `withInitScripts` overlay.
4. **Spring profile `scenario`.** No collisions with existing profile usage.
5. **Real upload via HTTP.** Each scenario uploads a real PDF; the request travels the same controllers, persistence, event-bus paths as production.
6. **No API key.** The stub seams never reach `LlmCallExecutor`.

## Q1: How does Spring resolve `@Bean @Primary` overrides for `LlmClassifier` and `LlmExtractor`?

`LlmClassifier` and `LlmExtractor` are both annotated `@Component` (final classes; concrete, not interfaces). Spring will pick the `@Bean @Primary` declaration in `ScenarioStubConfig` over the auto-detected `@Component` when the `scenario` profile is active.

`RetypeFlowIT` already demonstrates this exact pattern: `@Bean LlmExtractor llmExtractor() { return mock(LlmExtractor.class); }` (line 502–505). The bean name `llmExtractor` matches the auto-discovered component name; combined with `@Primary` (or the implicit primary status when only one bean of that type exists in the test profile), Spring uses the test-supplied instance everywhere `LlmExtractor` is autowired.

**Concrete approach:** `ScenarioStubConfig` is a `@TestConfiguration` annotated `@Profile("scenario")`. It declares two beans:

```java
@Bean @Primary LlmClassifier llmClassifier(ScenarioContext ctx) { ... }
@Bean @Primary LlmExtractor  llmExtractor(ScenarioContext ctx, ...) { ... }
```

Both beans subclass the production class (or — cleaner — implement against a small extracted interface). The cleanest path: subclass and override the public methods to consult `ScenarioContext` for the active fixture's canned values. Subclassing avoids a refactor of the production code.

## Q2: How does the stub know which fixture is active?

The scenario test class reads its fixture, hands it to a request-scoped (or test-scoped) `ScenarioContext` bean, and the stubs read that bean inside `classify(...)` / `extractFields(...)` / `extract(...)`.

**Approach:** `ScenarioContext` is a singleton bean, mutable, with `setActive(Fixture)` called in the test's `@BeforeEach` after fixture-loading. Each test runs sequentially within a class; cross-class isolation is unnecessary because Spring drops and re-creates the context between test classes. The mutability is acceptable inside the `scenario` profile — it is a test-only construct.

For the two-uploads concurrent scenario (#5), the `ScenarioContext` carries a list of fixtures keyed by document filename or upload index; the stub matches by `storedDocumentId` → which raw text was passed → which fixture wins. Concrete tie-breaking method: stubs look up the fixture by `organizationId` + the `rawText` substring or by storedDocumentId-to-fixture mapping populated when each upload completes its Step 1 (filename is sufficient because each scenario uses distinct filenames).

## Q3: How do canned classification errors surface?

`LlmClassifier.classify(...)` can throw `LlmSchemaViolation`, `LlmUnavailable`, `LlmTimeout`, `LlmProtocolError` (per c3-pipeline-spec §3.6). The stub surfaces these by reading the fixture's `classification.error` field — if present, throw the matching typed exception; otherwise return `new ClassifyResult(fixture.classification.docType)`.

The fixture schema names the exception by short string (`SCHEMA_VIOLATION`, `UNAVAILABLE`, `TIMEOUT`, `PROTOCOL_ERROR`). The loader maps to the typed exception; an unknown error name fails fixture-load.

Same approach for `LlmExtractor.extractFields(...)` and `LlmExtractor.extract(...)`.

## Q4: How are SSE assertions made in scenario tests?

`HappyPathSmokeTest` doesn't assert on SSE; `RetypeFlowIT` uses an `ApplicationEventPublisher` recorder bean. For the scenario suite, two layers of assertion:

1. **Internal event bus** — a recorder bean (similar to `RetypeFlowIT.DocumentStateChangedRecorder`) captures published `DocumentStateChanged` and `ProcessingStepChanged` events. This is what the `expectedEndState.events` block in the fixture asserts against. Cheap, deterministic.
2. **HTTP SSE stream** — only the concurrent-uploads scenario (#5) needs to assert on SSE. Use a `RestTemplate` GET that consumes the stream for a bounded window (e.g., 30 seconds with a `Duration.ofMillis(...)` read timeout), buffering received frames into a list. After uploads complete, decode the buffered frames and assert both documents appear with the expected events.

Java HTTP client + InputStream reads work fine for this. No SSE library required. Library dep would be `org.springframework:spring-webflux`'s `WebClient`, but for two events on a 30-second window the platform `RestTemplate` plus a manual frame parser is enough — copies the spec test approach.

## Q5: Does Awaitility's bounded-wait pattern suffice for the orchestrator-async paths?

Yes. `RetypeFlowIT` already uses `await().atMost(Duration.ofSeconds(5))` for the synchronous-sounding-but-async listener paths. For full upload-to-review the wait window is longer because it includes PDFBox + the two stubbed LLM calls + the workflow listener materialization. 30 seconds is generous. Polling intervals: 200 ms.

## Q6: What about the `LlmCallAuditWriter` invariant?

`LlmCallAudit` rows are written on every classify/extract call (per c3-pipeline-spec §3.7–§3.8). The stubs call the writer too — they construct an audit record with the appropriate fields and `error` set to the canned error message (or null on success), then delegate to the real `LlmCallAuditWriter`. This preserves the invariant that an integration-tested system looks identical at the audit-table level whether it ran live or stubbed.

The audit writer is injected into the stubs the same way it's injected into the production classes.

## Q7: Why not the lower seam (`LlmCallExecutor`)?

Three reasons:

1. **Coupling to the SDK.** Stubbing `LlmCallExecutor` requires fixtures to express `MessageCreateParams` and `JsonValue` — that's tool-call wire format. Any change to `MessageContentBuilder` or `ToolSchemaBuilder` ripples into every fixture.
2. **Redundant coverage.** `MessageContentBuilder` and `ToolSchemaBuilder` have unit tests already. Driving them through scenario fixtures would re-test what unit tests cover.
3. **Conceptual mismatch.** A scenario describes a story ("the LLM classified this as a Pinnacle invoice"). It does not describe a wire interaction ("the LLM emitted this exact tool-use block"). Stubbing at the high seam keeps the fixtures readable and stable.

## Q8: Is `@DirtiesContext` needed between scenarios?

For tests in the same class running sequentially, no — `@BeforeEach` truncates DB tables (the `RetypeFlowIT` pattern: `DELETE FROM workflow_instances; DELETE FROM documents; DELETE FROM stored_documents;` plus `recorder.reset()`). For different scenario test classes, Spring rebuilds the context between classes by default (different bean configurations would force this; same config reuses the cached context). The scenario suite uses one base test class + parameterized tests, so the context is reused — `@BeforeEach` cleanup is sufficient.

## Q9: How are concurrent uploads modeled?

Scenario #5 (two concurrent uploads → both flow correctly, single SSE stream surfaces both):

1. Fixture file declares `inputs: [pdf-1, pdf-2]` and `expectedEndState.events: [...]` covering both documents.
2. Test method opens an SSE GET in a thread, then submits both uploads via `RestTemplate` with virtual-thread executor (the project already enables virtual threads, per `RetypeFlowIT` properties: `spring.threads.virtual.enabled=true`).
3. Awaitility waits until both documents reach `AWAITING_REVIEW`.
4. Decode buffered SSE frames, assert each document's events appear in order.

`ScenarioContext` carries both fixtures keyed by filename. The stubs distinguish by passing `processingDocumentId` → which document's `rawText` arrived in `LlmClassifier.classify(...)` → which fixture matches. The match key is the upload filename embedded in `StoredDocument.sourceFilename`, traversable through `processingDocumentId`.

A simpler alternative: the stubs match by exact `rawText` content — different PDFs produce different text, so the match is unambiguous. Recommend this; it is robust against any future refactor of the lookup chain.

## Risks identified

- **Test wall time.** 12 scenarios × full Spring boot is heavy. Mitigation: `@SpringBootTest`'s context-reuse cache shares the context across all 12 scenario tests if they share the same configuration class. Practical wall time estimate: 1 startup (~30 s) + 12 × 5–30 s per scenario = 2–8 min. Within the 600 s Stop hook timeout.
- **Flakiness from async event ordering.** Mitigation: bounded Awaitility waits; assertion on event content (not strict ordering) where ordering is not part of the contract.
- **Concurrent-upload scenario flakiness.** Mitigation: stable match by `rawText` exact content; do not rely on upload timing; recorder bean captures events with timestamps the test orders deterministically.
- **PDFBox boot cost.** Mitigated already — `LlmClassifier` and `LlmExtractor` are stubbed, the orchestrator and PDFBox are real, and PDFBox loading is fast (microseconds per page on the small samples).
