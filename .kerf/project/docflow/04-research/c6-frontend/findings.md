# C6 Frontend — Research Findings

**Post-research revision (component walkthrough with user).** Document lifecycle restructured into 3 entities: `StoredDocument` (C2), `ProcessingDocument` (C3, transient), `Document` (C4, processed). Frontend impact: dashboard renders two sections (Processing in-flight at top, Documents below); detail view shape depends on entity type; SSE event vocabulary expands to include processing events. Library recommendations (react-pdf, react-hook-form + zod, Vitest, TanStack Query) unchanged.

Pass 4 research for the Frontend (React SPA). Stack locked: React 19, TypeScript, Vite 8, Playwright for E2E, native `EventSource` for SSE.

---

## 1. PDF rendering library

| Option | Footprint | DX | Multi-page/zoom | Verdict |
|---|---|---|---|---|
| (a) `<iframe src="/api/documents/{id}/file">` | 0 bytes | Trivial | Browser-native chrome — inconsistent across Chrome/Firefox/Safari, un-styleable | Zero cost, zero control |
| **(b) `react-pdf` (wraps `pdfjs-dist`)** | ~200 KB worker (async chunk) + ~10 KB wrapper; v10.4.1; React ≥ 16.8 | `<Document>` + `<Page>`; `scale` prop; `pageNumber` state | Full control — state-driven | Best balance |
| (c) `pdfjs-dist` directly | Same ~200 KB, no wrapper | Hand-roll canvas mount + worker config | Full control | More work than (b) for zero benefit |
| (d) Adobe View SDK | External CDN + Adobe account | High-level, licensed | Yes | Overkill |

### Recommendation: **`react-pdf` v10**

Mockups show a dark-panel `#2a2a2e` PDF viewer chrome rendered by the app, not the browser. An iframe hands control of the chrome to the browser — inconsistent, un-styleable. Bundle cost is the worker loaded async (separate chunk), which Vite handles cleanly via `?url` import.

```tsx
<Document file={`/api/documents/${id}/file`} onLoadSuccess={({numPages}) => setNumPages(numPages)}>
  <Page pageNumber={page} scale={scale} />
</Document>
```

Worker wiring (once):

```ts
// src/pdf-worker.ts
import { pdfjs } from 'react-pdf';
import workerSrc from 'pdfjs-dist/build/pdf.worker.min.mjs?url';
pdfjs.GlobalWorkerOptions.workerSrc = workerSrc;
```

**Risk.** PDF.js worker config is the #1 gotcha with Vite. Pin `react-pdf` and `pdfjs-dist` to compatible majors.

---

## 2. Dynamic schema-driven form

| Option | Footprint | DX for 5 types + array | Verdict |
|---|---|---|---|
| **(a) `react-hook-form` v7 + `zod` + hand-rolled fields** | ~15 KB gz RHF + ~8 KB gz zod | `useFieldArray` with stable keys; `zodResolver`; typed via generics | Right-sized |
| (b) `@rjsf/core` | ~175 KB default, 232+ KB w/ MUI theme | JSON-Schema-shaped; heavy; UX polish on custom layouts is constant fight | Overweight |
| (c) Hand-rolled + `useState` | 0 KB | Flat fields OK; array-of-object with stable keys error-prone | Reinvents the wheel |
| (d) TanStack Form | ~12 KB gz | Newer, smaller community | Viable but less community material |

### Recommendation: **`react-hook-form` v7 + `zod`**

C1's schema is domain-shaped, not JSON-Schema-shaped. Mapping C1 ↔ JSON Schema to feed RJSF adds indirection for zero gain. `useFieldArray` gives stable row keys (`field.id`, not `index`).

```tsx
function renderField(schema: FieldSchema, path: string) {
  switch (schema.type) {
    case 'string':  return <Input {...register(path)} />;
    case 'date':    return <Input type="date" {...register(path)} />;
    case 'decimal': return <Input type="number" step="0.01" {...register(path)} />;
    case 'enum':    return <Select options={schema.values} {...register(path)} />;
    case 'array':   return <FieldArray subSchema={schema.of} name={path} />;
  }
}
```

Stay on **v7** — v8 beta (2026-01-11) has breaking `useFieldArray` changes.

**Risk.** Decimal locale: `<input type="number">` uses `,` as decimal separator in some locales. Parse/normalize on submit.

---

## 3. Testing stack confirmation

| Concern | Tool | Version |
|---|---|---|
| Unit + component | Vitest 3.x | Supports Vite 6/7/8 |
| DOM | `@testing-library/react` v16+ | React 19 support |
| Events | `@testing-library/user-event` v14+ | Stable |
| DOM env | jsdom (default) | Fine |
| Mock HTTP | MSW 2.x | Node 20+ |
| E2E | Playwright 1.55+ | Chromium/WebKit parity |

### Recommendation: **Vitest 3 + RTL v16 + user-event v14 + jsdom + MSW 2; Playwright 1.55+**

React 19 test gotchas:
1. `use(promise)` suspends — wrap `render` in `<Suspense fallback={null}>` and use `findBy...`.
2. Actions / `useOptimistic` — use `waitFor` / `findBy...` for final state.
3. `act()` import: use `import { act } from 'react'` (the `react-dom/test-utils` path is deprecated).

**Coverage bar:** **70% line / 60% branch** — replaces the `0` placeholder per C7-R6a. Enough to catch obvious regressions without backend-style discipline.

---

## 4. Agent-driven exploratory testing

### Approach A: Playwright MCP (`@playwright/mcp`)

**Install.** `claude mcp add playwright npx @playwright/mcp@latest`

**Use.** In a Claude Code session with docker-compose running: "use playwright mcp — navigate to http://localhost:5173, click into Pinnacle, upload samples/pinnacle-legal/invoices/inv-001.pdf, wait for the row to reach Review, verify all schema fields render, approve through to Filed. Report any JS console errors and broken routes."

**Behavior.** Real Chrome window; accessibility-tree-driven (not pixels); structured snapshots returned per tool call. ~50 tools.

**Catches.** Page-render failures, missing elements, broken routes, thrown exceptions (console listener), network 4xx/5xx, semantic drift vs. spec.

**Reproducible in CI?** Not directly — LLM-driven, tokens, path varies. The agent can **emit a Playwright spec file** from its successful path; commit that and CI runs the spec.

**Token cost.** ~114K per task via MCP vs ~27K via CLI (4× cheaper). OK for one or two exploratory sessions.

### Approach B: browser-use (Python)

Separate tool, separate LLM API bill. Not the right tool when Claude Code is already in hand.

### Approach C: Direct Playwright tests authored by the agent

Plain specs, run via `npm run test:e2e`. Deterministic, reproducible, cheap. What C6-R14 already requires.

**Misses:** unknown-unknowns.

### Recommendation: **C as the contract, A as an optional manual tool.**

- **Ship Approach C** — Playwright tests in `tests/e2e/` per C6-R14, authored by the agent, reproducible in CI. Covers the required journeys.
- **Document Approach A** in the README as an optional manual tool: install command in `AGENTS.md`, one-paragraph recipe, don't wire into CI.
- **Skip browser-use** — no additional catch for a 3-4 day take-home.

**Investment envelope.** ~2 hours to write two Playwright specs well (happy path + flag-and-resolve). ~30 minutes to document the MCP recipe.

---

## 5. SSE client lifecycle

### Recommendation: One hook, one `EventSource` per org, dispatch to TanStack Query cache.

```ts
// src/hooks/useDocumentEvents.ts  (consider renaming to useOrgEvents — now spans
// processing and document events, not just documents)
export function useDocumentEvents(orgId: string) {
  const qc = useQueryClient();

  useEffect(() => {
    if (!orgId) return;
    const es = new EventSource(`/api/organizations/${orgId}/stream`);

    const handlers: Record<string, (p: any) => void> = {
      ProcessingStepChanged: (p) => {
        qc.setQueryData(['processing', p.processingDocumentId], (old: any) =>
          old ? { ...old, currentStep: p.currentStep } : old);
        qc.invalidateQueries({ queryKey: ['dashboard', orgId] });
      },
      ProcessingFailed: (p) => qc.invalidateQueries({ queryKey: ['processing', p.processingDocumentId] }),
      ProcessingCompleted: (p) => {
        // Processing done; processing-document is gone, new Document arrived.
        qc.invalidateQueries({ queryKey: ['dashboard', orgId] });
        qc.invalidateQueries({ queryKey: ['document', p.documentId] });
      },
      DocumentStateChanged: (p) => {
        qc.setQueryData(['document', p.documentId], (old: any) =>
          old ? { ...old, currentStage: p.currentStage, currentStatus: p.currentStatus } : old);
        qc.invalidateQueries({ queryKey: ['dashboard', orgId] });
      },
      DocumentReextractionStarted: (p) => {
        qc.setQueryData(['document', p.documentId], (old: any) =>
          old ? { ...old, reextractionStatus: 'IN_PROGRESS' } : old);
      },
      DocumentReextractionCompleted: (p) => qc.invalidateQueries({ queryKey: ['document', p.documentId] }),
      DocumentReextractionFailed: (p) => qc.invalidateQueries({ queryKey: ['document', p.documentId] }),
    };

    for (const type of Object.keys(handlers)) {
      es.addEventListener(type, (e: MessageEvent) => handlers[type](JSON.parse(e.data)));
    }
    es.onerror = () => { /* EventSource auto-reconnects */ };

    return () => es.close();
  }, [orgId, qc]);
}
```

- Native `EventSource` handles backoff per C5-R8's `retry: 5000` hint. No polyfill.
- `setQueryData` is a surgical update. `invalidateQueries` on the dashboard key handles sort-order re-ordering and the cross-section transition when a `ProcessingCompleted` event moves a row from the Processing section to the Documents section.
- Spring's `SseEmitter` emits `event:` names — use `addEventListener(type, …)`.
- `Last-Event-ID` out of scope per C5-R8; reconnect rehydrates via next REST fetch.
- Hook name: `useDocumentEvents` is now a misnomer since the stream carries both processing and document events. `useOrgEvents(orgId)` better reflects the scope — rename in implementation.

Define typed `DocFlowEvent` discriminated union in `src/types/events.ts` mirroring C5-R8.

---

## 6. State management

| Option | Fit | Verdict |
|---|---|---|
| `useState` + Context | Context re-renders all consumers on every update | Reject |
| Redux Toolkit | Over-engineered for 10 screens | Reject |
| TanStack Query + Zustand | TanStack handles REST + SSE; Zustand ~1 KB for modal state | Good |
| **TanStack Query + React Router URL params, no Zustand** | Org in URL, modals in local `useState`, form in RHF | **Minimum** |

### Recommendation: **TanStack Query + React Router URL params. No Zustand/Redux.**

- **Server state** → TanStack Query (REST + SSE updates + cache invalidation).
- **Selected org** → URL (`/org/:orgId/dashboard`, `/org/:orgId/document/:docId`). Free bookmarkability, back-button, deep-linking.
- **Modal open/close** → `useState` in the owning component.
- **Form state** → `react-hook-form`.

**Query key layout for the 3-entity split.** The dashboard query key `['dashboard', orgId]` returns the combined `{processing, documents}` shape from the API and drives the two-section render (Processing in-flight at top, Documents below). Detail views use per-id keys: `['processing', id]` for `ProcessingDocument` detail (file preview + step indicator + retry-on-failure) and `['document', id]` for `Document` detail (full Review/Approval UI). SSE handlers invalidate `['dashboard', orgId]` whenever an item crosses sections (e.g., `ProcessingCompleted` removes from Processing and adds to Documents).

Zero global-state library, zero boilerplate. If a real case appears later (toast queue, upload tracker surviving route change), add Zustand then — don't pre-build.

Upload optimistic UI uses TanStack Query `onMutate`/rollback:

```ts
useMutation({
  mutationFn: uploadFile,
  onMutate: async (file) => {
    await qc.cancelQueries({ queryKey: ['documents', orgId] });
    const prev = qc.getQueryData(['documents', orgId]);
    qc.setQueryData(['documents', orgId], (old: Doc[]) => [{...pending}, ...old]);
    return { prev };
  },
  onError: (_e, _v, ctx) => qc.setQueryData(['documents', orgId], ctx?.prev),
  onSettled: () => qc.invalidateQueries({ queryKey: ['documents', orgId] }),
});
```

---

## Risks & unknowns

1. **`react-pdf` + Vite 8 worker config drift.** Pin versions; lock the `?url` recipe in one file.
2. **React 19 `use()` + Suspense under RTL.** Prefer `useQuery` for async data; enable suspense only when opted in.
3. **SSE behind Vite dev proxy.** Proxy sometimes buffers, breaking SSE. Configure `server.proxy['/api'].changeOrigin = true` and test manually.
4. **RHF v7 vs v8.** Stay on v7 until v8 stabilizes.
5. **Playwright MCP token budget.** OK for one-off sweeps; don't loop.
6. **Decimal/date locale.** Parse-on-submit in the zod resolver; don't rely on input widget.
7. **MCP artifacts.** Don't commit screenshots/traces from agent runs; commit the Playwright *test files* the agent wrote.
8. **Coverage threshold placeholder.** C7-R6a commits `0` in `vitest.config.ts`. Pass 5 replaces with 70% line / 60% branch.

---

## Summary (for Pass 5 change-spec)

| Question | Pick |
|---|---|
| PDF viewer | `react-pdf` v10; PDF.js worker via Vite `?url` import |
| Dynamic form | `react-hook-form` v7 + `zod` + `useFieldArray`; 5-branch `renderField` dispatch |
| Test stack | Vitest 3 + RTL v16 + user-event v14 + jsdom + MSW 2; Playwright 1.55+ |
| Coverage bar | 70% line / 60% branch |
| Agent exploratory testing | Ship Playwright specs as the contract; document `@playwright/mcp` as optional manual tool |
| SSE client | One `useOrgEvents(orgId)` hook (was `useDocumentEvents`); native `EventSource`; handlers route `Processing*` events to `['processing', id]` cache and `Document*`/`Reextraction*` events to `['document', id]` cache; both invalidate `['dashboard', orgId]` |
| Dashboard structure | Two sections from one query (`['dashboard', orgId]` → `{processing, documents}`): Processing (in-flight, small rows with step badges + retry) on top, Documents (processed, large rows filtered by `canonicalStatus ∈ {AWAITING_REVIEW, FLAGGED, AWAITING_APPROVAL, FILED, REJECTED}`) below |
| Detail views | Shape depends on entity type: `ProcessingDocument` detail = file preview + processing-step indicator + retry-on-failure (limited info); `Document` detail = full Review/Approval UI with dynamic schema form |
| State management | TanStack Query + React Router URL params; no Zustand/Redux |

---

## Sources

- react-pdf: https://www.npmjs.com/package/react-pdf
- React 19 release: https://react.dev/blog/2024/12/05/react-19
- useFieldArray: https://react-hook-form.com/docs/usefieldarray
- Vitest 3 + React 19 compatibility: https://www.thecandidstartup.org/2025/03/31/vitest-3-vite-6-react-19.html
- Playwright MCP: https://github.com/microsoft/playwright-mcp
- browser-use: https://github.com/browser-use/browser-use
- TanStack Query SSE: https://github.com/TanStack/query/discussions/418
