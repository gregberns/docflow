# C6 Frontend (React SPA) — Change Spec (Pass 5)

Component: **C6 — Frontend (React SPA)**
Inputs: `03-components.md` §C6 (C6-R1..R14), `04-research/c6-frontend/findings.md`, `02-analysis.md` §2 (mockups), `problem-statement/mockups/`.
Consumer of: C5 (REST + SSE).
Producer for: end users only — no other component depends on C6.

---

## 1. Requirements (carried forward, with traceability)

| ID | Requirement (summarized) | Source |
|---|---|---|
| **C6-R1** | Org picker lists `/api/organizations`; each card shows icon, name, supported doc-types, `inProgressCount` and `filedCount` stat badges; selecting an org navigates to its dashboard. | 03-components §C6 |
| **C6-R2** | Dashboard renders **stats bar** (`inProgress`, `awaitingReview`, `flagged`, `filedThisMonth`), **Processing section** (`processing[]`, small rows, step badges, reduced opacity, spinner, inline failure), and **Documents section** (`documents[]` with `status` + `docType` filters), all from one `GET /api/organizations/{orgId}/documents` response. | 03-components §C6 |
| **C6-R3** | Processing rows are non-clickable; `currentStep = FAILED` rows show inline failure with no retry affordance — recovery is re-upload. | 03-components §C6 |
| **C6-R4** | Dashboard upload button POSTs to `/api/organizations/{orgId}/documents`; uploaded file appears in the Processing section and updates live via SSE. | 03-components §C6 |
| **C6-R5** | Dashboard subscribes to `/api/organizations/{orgId}/stream`; on **any** `ProcessingStepChanged` or `DocumentStateChanged`, invalidates the dashboard query and refetches. Detail view refetches `['document', id]` on `DocumentStateChanged` for that id. SSE connection closes on unmount. | 03-components §C6 |
| **C6-R6** | Clicking a documents-section row opens `/documents/{id}`; clicking a processing-section row is a no-op. | 03-components §C6 |
| **C6-R7** | Only processed `Document`s have a detail route (`/documents/{id}`), sourced from `GET /api/documents/{id}`, with two-panel PDF + form layout. **No `/processing-documents/{id}` route.** | 03-components §C6 |
| **C6-R8** | Form panel content is keyed off `currentStage` and `reextractionStatus`: re-extract IN_PROGRESS banner / disabled form; FAILED banner with previous values; Review (editable, dropdown, Approve/Reject); Review-flagged (banner + Resolve replaces Approve); Approval (read-only summary, Approve/Flag, role label if configured); Filed/Rejected (read-only, Back to Documents). Array-of-object fields are read-only tables in Approval / Filed / Rejected. | 03-components §C6 |
| **C6-R9** | Stage progress indicator synthesizes pre-workflow processing steps (`ProcessingDocument.currentStep`) with workflow stages (`WorkflowInstance.currentStageId`) into one visual sequence. Rejected terminal state: incoming segment red, `Rejected` red current; never-reached approval stages muted. | 03-components §C6 |
| **C6-R10** | Review form supports string, date, decimal, enum, and array-of-object field types; arrays render with **inline-editable cells + add/remove-row controls** in the Review stage only. | 03-components §C6 |
| **C6-R11** | Changing the document type in Review opens a confirm modal; on confirm, POSTs to `/api/documents/{id}/review/retype`; transitions to `reextractionStatus = IN_PROGRESS` per C6-R8. Document does not leave Review. | 03-components §C6 |
| **C6-R12** | Approval-stage Flag opens a modal with required comment textarea; submit disabled until non-empty; on submit posts the `Flag` action. | 03-components §C6 |
| **C6-R13** | Component tests cover stage progress, review form (one schema per client), flag modal validation, reclassify modal confirm/cancel. | 03-components §C6 |
| **C6-R14** | E2E suite runs against the dockerized stack: happy path + flag-and-resolve. | 03-components §C6 |

Pass 5 also resolves the parked **frontend coverage threshold** (research §3): **70% line / 60% branch**.

---

## 2. Research summary

From `04-research/c6-frontend/findings.md`:

- **PDF rendering** → `react-pdf` v10 (PDF.js worker wired via Vite `?url` import). Mockup chrome (`#2a2a2e` dark panel) is app-rendered, not browser-default.
- **Dynamic form** → `react-hook-form` v7 + `zod` + `useFieldArray`. Stay on v7 (v8 beta has breaking `useFieldArray` changes).
- **Test stack** → Vitest 3 + RTL v16 + `@testing-library/user-event` v14 + jsdom + MSW 2; Playwright 1.55+ for E2E.
- **Coverage bar** → 70% line / 60% branch (replaces the placeholder `0` in `vitest.config.ts` carried by C7).
- **SSE consumer** → one hook (`useOrgEvents(orgId)`), native `EventSource`, refetch-on-any-event (no surgical cache mutations) per C6-R5. Renamed from research draft `useDocumentEvents` because the stream now carries both processing and document events.
- **State management** → TanStack Query for server state, React Router URL params for navigation (`/org/:orgId/dashboard`, `/documents/:docId`), `useState` for local modal state, `react-hook-form` for form state. No Zustand, no Redux.
- **Agent exploratory testing** → ship Playwright specs as the contract; document `@playwright/mcp` as an optional manual tool in `AGENTS.md`. Don't wire MCP into CI.

Stack lock: React 19, TypeScript (strict), Vite 8, ESLint, Prettier.

---

## 3. Approach

### 3.1 Routing (React Router v7, file-less data routes)

```
/                                 OrgPickerPage           (C6-R1)
/org/:orgId/dashboard             DashboardPage           (C6-R2..R6)
/documents/:documentId            DocumentDetailPage      (C6-R7..R12)
*                                 NotFoundPage
```

- Selected org is encoded **in the URL path**, not in client storage. Bookmarkable, back-button-safe.
- No `/processing-documents/:id` route (C6-R7). The dashboard processing section is the entire UI for in-flight documents.

### 3.2 Component tree

```
<App>
  <QueryClientProvider>
    <RouterProvider>
      OrgPickerPage
        OrgPickerCard            (icon + name + doc-types + inProgressCount + filedCount badges)
      DashboardPage
        AppTopbar                (org name + switch link)
        DashboardStatsBar        (inProgress / awaitingReview / flagged / filedThisMonth)
        DashboardFilterBar       (status dropdown, docType dropdown, Upload button)
        ProcessingSection
          ProcessingRow*         (filename, StepBadge, spinner, opacity 0.55, inline failure)
        DocumentsSection
          DocumentRow*           (clickable → /documents/{id})
      DocumentDetailPage
        DetailLayout             (left: PdfViewer; right: FormPanel)
          PdfViewer              (react-pdf <Document><Page/>)
          DocumentHeader
          StageProgress          (synthesizes processing steps + workflow stages)
          FormPanel              (mode dispatched by currentStage + reextractionStatus)
            ReviewForm           (editable; ReclassifyModal; ActionBar Approve/Reject/Resolve)
              FieldArrayTable    (inline-editable rows + add/remove)
            ApprovalSummary      (read-only; FlagModal; ActionBar Approve/Flag)
              ReadOnlyArrayTable
            TerminalSummary      (Filed/Rejected; BackToDocumentsButton)
          ReextractionInProgressBanner
          ReextractionFailedBanner
          FlagBanner             (when in Review with originStage)
```

### 3.3 State management

| Concern | Mechanism |
|---|---|
| Server state (orgs, dashboard, document detail, workflow defs) | TanStack Query |
| Selected org | URL path param `:orgId` |
| Selected document | URL path param `:documentId` |
| Modal open/close (reclassify, flag) | local `useState` |
| Form state (Review) | `react-hook-form` + `zod` resolver |

**Query keys**

| Key | Endpoint | Notes |
|---|---|---|
| `['organizations']` | `GET /api/organizations` | Org picker source. |
| `['dashboard', orgId]` | `GET /api/organizations/{orgId}/documents` | Returns `{ stats, processing, documents }` per C5-R3. Drives stats bar + both list sections in one fetch. |
| `['document', documentId]` | `GET /api/documents/{id}` | `DocumentView` shape; drives detail page. |
| `['workflow', orgId, docTypeId]` | `GET /api/organizations/{orgId}/doctypes/{docTypeId}/workflow` | Stage list for `StageProgress`. |
| `['file', documentId]` | URL string only | Used as `<Document file=...>` source in `react-pdf`; not a TanStack key. |

**No `['processing', id]` query.** The dashboard query is the only owner of `ProcessingDocumentSummary[]`. There is no detail view for in-flight documents per C6-R7, so no per-id query is needed. (This deviates from the research-draft sketch of `['processing', id]` — see §9 contradictions.)

### 3.4 SSE consumer (refetch-on-any-event)

`src/hooks/useOrgEvents.ts` (renamed from research's `useDocumentEvents`).

```ts
export function useOrgEvents(orgId: string) {
  const qc = useQueryClient();
  useEffect(() => {
    if (!orgId) return;
    const es = new EventSource(`/api/organizations/${orgId}/stream`);
    const refetchDashboard = () =>
      qc.invalidateQueries({ queryKey: ['dashboard', orgId] });
    const refetchDocument = (id: string) =>
      qc.invalidateQueries({ queryKey: ['document', id] });

    es.addEventListener('ProcessingStepChanged', () => refetchDashboard());
    es.addEventListener('DocumentStateChanged', (e: MessageEvent) => {
      const p = JSON.parse(e.data) as { documentId: string };
      refetchDashboard();
      refetchDocument(p.documentId);
    });
    es.onerror = () => { /* native EventSource auto-reconnects */ };
    return () => es.close();
  }, [orgId, qc]);
}
```

- Refetch-on-any-event per C6-R5. No surgical `setQueryData`. The dashboard endpoint is cheap at take-home scale; correctness beats cleverness.
- Detail page calls `useOrgEvents(orgId)` and additionally subscribes to its own `DocumentStateChanged` to refetch `['document', documentId]` (filter inside the listener — the stream is org-scoped, not document-scoped).
- Native `EventSource` handles reconnect/backoff per C5-R8's `retry: 5000` hint. No polyfill, no `Last-Event-ID` handling.
- Closing on unmount tested per C6-R5.

The C5 SSE event menu is exactly two events: `ProcessingStepChanged` and `DocumentStateChanged`. The `Reextraction*` events from the research draft are not part of the C5 contract — retype lifecycle reaches the frontend via `DocumentStateChanged` payloads carrying `reextractionStatus` per the cross-component summary table.

### 3.5 React Query patterns

- **Dashboard query.** `staleTime: 0`, `refetchOnWindowFocus: false`. SSE drives invalidation; stale-time isn't doing useful work.
- **Document query.** Same configuration; lives only while the detail route is mounted.
- **Action mutations** (Approve, Reject, Flag, Resolve, Retype). On `onSuccess`, invalidate `['document', id]` and `['dashboard', orgId]`. No optimistic update on action mutations — the UI waits for the server's `DocumentView` response. Stage transitions are infrequent and a brief disabled-button window is acceptable.
- **Upload mutation.** Optimistic insert into `['dashboard', orgId].processing` so the row appears immediately; `onError` rollback; `onSettled` invalidate. Sketch from research §6 retained but typed against `ProcessingDocumentSummary`.
- **Stale data on edit collision.** Stale-edit collisions surface as C5's generic `500 INTERNAL_ERROR` (no dedicated code; C4 maps optimistic-lock overflow to the catch-all per the cross-spec resolution). The mutation's `onError` shows a generic "save failed; refresh and retry" toast and invalidates `['document', id]` to pull current state. See §7.

### 3.6 Dynamic form (C6-R10)

A 5-branch `renderField(schema, path)` dispatch over the C1 field schema (string / date / decimal / enum / array). Array-of-object fields use `useFieldArray`, render a table with one row per element, and expose add/remove-row controls **only when the form mode is Review**. Approval/Filed/Rejected modes render `<ReadOnlyArrayTable>` instead — same data, no inputs, no row controls (mockup alignment with the Pinnacle Approval and Filed mockups).

Decimal fields normalize on submit (`zod` `.preprocess` parses both `"1,234.56"` and `"1234.56"`) — research-flagged locale risk.

### 3.7 Stage progress (C6-R9)

Single component, two visual segments synthesized from two data sources joined client-side:

- **Pre-workflow segment.** Always `Text Extracting → Classifying → Extracting`. Source: `ProcessingDocument.currentStep` for in-flight, or implicit "all done" for processed.
- **Workflow segment.** Source: workflow definition (`['workflow', ...]` query) + `WorkflowInstance.currentStageId`.

Rendering states:

| Doc state | Pre-workflow segment | Workflow segment |
|---|---|---|
| In-flight, current step `TEXT_EXTRACTING` / `CLASSIFYING` / `EXTRACTING` | Current step highlighted; later steps muted; workflow segment all muted | All muted |
| In-flight, `currentStep = FAILED` | Failed step red; later steps muted | All muted |
| Processed, in Review (not flagged) | All green | Up through `Review` highlighted (current); later muted |
| Processed, in Review-flagged | All green | Through originStage muted-green (regressed amber on the segment between originStage and Review); Review highlighted |
| Processed, in approval | All green | Up through current approval highlighted (pink); later muted |
| Processed, Filed | All green | All green |
| Processed, Rejected | All green | Stages reached → green; segment leading into Rejected → red; Rejected → red current; never-reached approvals → muted |

The "regressed amber" for flagged is mockup-derived (`02-review-flagged-pinnacle.html`).

### 3.8 Mockup-illustrative-only items (do not implement)

- **Pagination controls** ("1–10 of 76") — visible in `01-dashboard-*.html` but explicitly not implemented per the component focus directive. Dashboard renders the full result set the API returns.
- **Sort affordances on column headers** — none implemented; default order is what the API returns (in-flight first, `uploadedAt DESC`).
- **Retry button on failed processing rows** — explicitly excluded per C6-R3.

---

## 4. Files & changes

All paths under `frontend/`. C7 owns the initial scaffold (`vite create`, `tsconfig`, ESLint/Prettier config, Vitest config, Playwright config); C6 populates application source.

### 4.1 New files

```
frontend/
  src/
    main.tsx                              entry; QueryClientProvider + RouterProvider
    App.tsx                               router shell
    pdf-worker.ts                         PDF.js worker wiring (Vite ?url import)
    routes/
      OrgPickerPage.tsx                   C6-R1
      DashboardPage.tsx                   C6-R2..R6
      DocumentDetailPage.tsx              C6-R7..R12
      NotFoundPage.tsx
    components/
      AppTopbar.tsx
      OrgPickerCard.tsx                   inProgressCount + filedCount badges
      DashboardStatsBar.tsx               inProgress / awaitingReview / flagged / filedThisMonth
      DashboardFilterBar.tsx              status (canonical WorkflowStatus), docType, Upload button
      ProcessingSection.tsx
      ProcessingRow.tsx                   non-clickable; FAILED inline state
      DocumentsSection.tsx
      DocumentRow.tsx                     clickable → /documents/{id}
      DetailLayout.tsx                    two-panel
      PdfViewer.tsx                       react-pdf <Document><Page/>
      DocumentHeader.tsx
      StageProgress.tsx                   synthesizes processing + workflow segments; rejected terminal styling
      FormPanel.tsx                       dispatch by currentStage + reextractionStatus
      ReviewForm.tsx                      RHF + zod; reclassify modal; Approve/Reject/Resolve
      ApprovalSummary.tsx                 read-only; Approve + Flag
      TerminalSummary.tsx                 Filed/Rejected; BackToDocumentsButton
      FieldArrayTable.tsx                 inline-edit rows + add/remove (Review only)
      ReadOnlyArrayTable.tsx              same data, no inputs (Approval/Filed/Rejected)
      FlagBanner.tsx
      ReextractionInProgressBanner.tsx
      ReextractionFailedBanner.tsx
      ReclassifyModal.tsx
      FlagModal.tsx                       required textarea; submit disabled when empty
      StepBadge.tsx                       Text Extracting / Classifying / Extracting / Failed
      StatusBadge.tsx                     canonical WorkflowStatus values only
      DocTypeBadge.tsx
    hooks/
      useOrgEvents.ts                     SSE consumer (refetch-on-any-event)
      useUploadDocument.ts                optimistic insert into dashboard.processing
      useDocumentActions.ts               approve / reject / flag / resolve / retype
    api/
      client.ts                           fetch wrapper; throws typed errors per C5-R9a
      organizations.ts                    GET /api/organizations
      dashboard.ts                        GET /api/organizations/{orgId}/documents
      documents.ts                        GET /api/documents/{id}; actions
      workflows.ts                        GET workflow definition
    types/
      readModels.ts                       ProcessingDocumentSummary, DocumentView (mirrors C5)
      workflow.ts                         WorkflowStatus enum (canonical 5 values)
      events.ts                           DocFlowEvent discriminated union
      schema.ts                           FieldSchema (string/date/decimal/enum/array)
    schemas/
      buildZodFromFieldSchema.ts          C1 schema → zod schema
    util/
      formatters.ts                       date/decimal display + parse helpers
  tests/
    unit/                                 Vitest specs (one per component listed in C6-R13)
    msw/handlers.ts                       MSW handlers mirroring C5 endpoints
  e2e/
    happy-path.spec.ts                    C6-R14 (Pinnacle Invoice end to end)
    flag-and-resolve.spec.ts              C6-R14
  index.html
  vite.config.ts                          dev proxy /api → backend; ?url worker support
  vitest.config.ts                        coverage thresholds 70 line / 60 branch
  playwright.config.ts
  tsconfig.json                           strict
  .eslintrc.cjs / eslint.config.js
  .prettierrc
  package.json
```

### 4.2 Files modified outside `frontend/`

- `AGENTS.md` — append a one-paragraph optional recipe for `@playwright/mcp` (install command + invocation example). Per the no-unprompted-production-caveats memory, the README is **not** updated with SSE proxy or operational notes.

### 4.3 Files explicitly NOT created

- No `/processing-documents/{id}` route module.
- No retry-on-failure component for processing rows.
- No `Pagination.tsx`.
- No global state store (no Zustand, no Redux).

---

## 5. Acceptance criteria

Each is concrete and testable — DOM elements, events, transitions.

### 5.1 Org picker (C6-R1)

- AC1.1 GET `/api/organizations` returns N orgs ⇒ exactly N `<article data-testid="org-card">` elements render.
- AC1.2 Each card renders the org `name`, the org `iconUrl` as `<img alt="...">`, the supported doc-types (one `<li>` per type), a badge `In Progress` with text matching `inProgressCount`, and a badge `Filed` with text matching `filedCount`.
- AC1.3 Clicking a card navigates to `/org/{orgId}/dashboard` (asserted via router location).

### 5.2 Dashboard stats bar (C6-R2)

- AC2.1 Renders four `<div data-testid="stat-{key}">` elements where `{key} ∈ {inProgress, awaitingReview, flagged, filedThisMonth}`. Text content matches `stats[key]`.
- AC2.2 Stats bar values do **not** change when the user toggles the status or docType filter (they are unfiltered totals).

### 5.3 Dashboard sections (C6-R2, C6-R3, C6-R6)

- AC3.1 The processing section renders one `<tr data-testid="processing-row">` per `processing[]` element with `data-step` attribute equal to one of `TEXT_EXTRACTING | CLASSIFYING | EXTRACTING | FAILED`.
- AC3.2 Processing rows have computed style `opacity: 0.55` and contain a `<span data-testid="spinner">` (except in `FAILED` state, where the spinner is replaced by an error message and `data-testid="processing-failure"` is present).
- AC3.3 Clicking any processing row does not change the router location.
- AC3.4 The documents section renders one `<tr data-testid="document-row">` per `documents[]` element. Clicking a row navigates to `/documents/{id}`.
- AC3.5 The status filter dropdown options are exactly the canonical `WorkflowStatus` values that appear in at least one of the org's configured workflows (e.g., `AWAITING_APPROVAL` is omitted for Riverside Receipt). Hidden options must not appear in `option` elements.
- AC3.6 The docType filter dropdown options are exactly the org's configured doc-types (e.g., "Retainer Agreement" is present only for Pinnacle).

### 5.4 Upload (C6-R4)

- AC4.1 Clicking the Upload button opens a native file picker (`<input type="file" accept="application/pdf,image/*">`).
- AC4.2 On file select, a POST to `/api/organizations/{orgId}/documents` is fired and a new processing row appears immediately (optimistic), then is reconciled by the server response.

### 5.5 SSE (C6-R5)

- AC5.1 Mounting `DashboardPage` opens exactly one `EventSource` to `/api/organizations/{orgId}/stream`.
- AC5.2 An incoming `ProcessingStepChanged` event triggers a refetch of `['dashboard', orgId]` (verified via MSW request count).
- AC5.3 An incoming `DocumentStateChanged` event triggers a refetch of `['dashboard', orgId]` and, when the detail page is open for that `documentId`, also triggers a refetch of `['document', documentId]`.
- AC5.4 Unmounting the page calls `EventSource.close()` (verified via spy).

### 5.6 Detail view & form panel (C6-R7, C6-R8)

- AC6.1 No matter how a user constructs `/processing-documents/{id}`, the route resolves to `NotFoundPage`. (Negative test for C6-R7.)
- AC6.2 `reextractionStatus = IN_PROGRESS` ⇒ a banner with text matching `Re-extracting as <newType>` is visible; all form inputs have `disabled`; the action bar has no Approve/Reject buttons.
- AC6.3 `reextractionStatus = FAILED` ⇒ an error banner is visible with the failure message; form inputs are re-enabled; the prior values are still bound (RHF state preserved).
- AC6.4 `currentStatus = AWAITING_REVIEW` and no `originStage` ⇒ the form is editable, the doc-type dropdown is rendered, and the action bar contains exactly two buttons: `Approve`, `Reject`.
- AC6.5 `currentStatus = AWAITING_REVIEW` and `originStage` is set ⇒ a `<div data-testid="flag-banner">` is rendered above the form with the origin-stage label and the prior comment; the action bar's `Approve` button is replaced by `Resolve`.
- AC6.6 `currentStatus = AWAITING_APPROVAL` ⇒ all inputs render as read-only display elements; array-of-object fields render as `<table data-testid="readonly-array">` with no input children and no add/remove controls; action bar contains `Approve` and `Flag`. If the workflow stage config supplies `role`, the stage label includes the role suffix (e.g., `Attorney Approval — role: Attorney`).
- AC6.7 `currentStatus = FILED` or `currentStatus = REJECTED` ⇒ form is read-only; arrays render as `<table data-testid="readonly-array">`; action bar contains exactly one button: `Back to Documents`.

### 5.7 Stage progress (C6-R9)

- AC7.1 In-flight, `currentStep = CLASSIFYING` ⇒ progress shows three pre-workflow segments; the `Classifying` segment has `data-state="current"`; later segments have `data-state="upcoming"`; workflow segments are all `data-state="upcoming"`.
- AC7.2 In-flight, `currentStep = FAILED` ⇒ the failed segment has `data-state="failed"` and a red color class.
- AC7.3 Processed, `currentStatus = REJECTED` ⇒ the segment immediately preceding the `Rejected` node has `data-state="rejected-edge"` (red); the `Rejected` node has `data-state="rejected-current"`; approval stages that exist in the workflow definition but were never reached have `data-state="muted"`.
- AC7.4 Pre-workflow and workflow segments render contiguously inside one parent `<ol data-testid="stage-progress">`.

### 5.8 Review form & arrays (C6-R10, C6-R11)

- AC8.1 For Pinnacle Invoice schema, the form contains inputs for every scalar field in C1's schema (string/date/decimal/enum) and one `<table data-testid="field-array-{name}">` for `lineItems`.
- AC8.2 The array table has one row per element; each cell is an editable input; an `Add row` button appends a row; per-row `Remove` removes that row.
- AC8.3 In Approval/Filed/Rejected modes, no `<input>` exists inside the array table and no `Add row` / `Remove` buttons are rendered.
- AC8.4 Selecting a different value in the doc-type dropdown opens `<div role="dialog" data-testid="reclassify-modal">`. `Cancel` closes the modal and reverts the dropdown. `Confirm` POSTs `/api/documents/{id}/review/retype` with the new type id. After a successful response, the form transitions to `reextractionStatus = IN_PROGRESS` (per AC6.2).

### 5.9 Flag modal (C6-R12)

- AC9.1 Clicking `Flag` on an approval stage opens `<div role="dialog" data-testid="flag-modal">` with a required `<textarea>`.
- AC9.2 The submit button has `disabled` while the textarea value is empty or whitespace-only; it becomes enabled when non-whitespace is present.
- AC9.3 On submit, POST `/api/documents/{id}/actions` is fired with body `{ "action": "Flag", "comment": <textarea> }`; on success, the modal closes and the document query is invalidated. (The earlier `/{id}/{stageId}/flag` URL was a Pass-5 spec drift; the C5 `/actions` endpoint is canonical.)

---

## 6. Verification

### 6.1 Local commands

| Concern | Command |
|---|---|
| Build | `cd frontend && npm run build` |
| Lint | `cd frontend && npm run lint` |
| Format check | `cd frontend && npm run format:check` |
| Type check | `cd frontend && npm run typecheck` |
| Unit + component tests | `cd frontend && npm run test` (Vitest) |
| Coverage gate | `cd frontend && npm run test -- --coverage` (fails below 70 line / 60 branch) |
| E2E (against compose) | `cd frontend && npm run test:e2e` (Playwright) |

### 6.2 Repo-level

`make test` (defined in C7) invokes the frontend portion as `npm run lint && npm run typecheck && npm run test -- --coverage && npm run build`. E2E is a separate `make test:e2e` target that requires `docker compose up` and is not part of the default `make test` (per CLAUDE.md "long-running tests are exempt from default fast-test runs").

### 6.3 Component tests required (C6-R13)

- `StageProgress` for at least: pre-workflow in-flight, Review (not flagged), Review-flagged, Approval, Filed, Rejected.
- `ReviewForm` for at least: Pinnacle Invoice (`lineItems`), Riverside Receipt (`category` enum), Ironworks Lien Waiver (`waiverType` enum). Asserts every C1 schema field renders.
- `FlagModal`: empty-comment submit blocked, non-empty submit fires action.
- `ReclassifyModal`: confirm fires retype, cancel reverts dropdown.

### 6.4 E2E tests required (C6-R14)

- `happy-path.spec.ts`: Pinnacle Invoice — upload `pinnacle-legal/invoices/inv-001.pdf` ⇒ row in Processing ⇒ row moves to Documents at `AWAITING_REVIEW` ⇒ approve through Review, Attorney Approval, Billing Approval ⇒ row at `FILED`.
- `flag-and-resolve.spec.ts`: same upload ⇒ approve from Review ⇒ flag-with-comment from Attorney Approval ⇒ asserts flag banner on Review with origin stage `ATTORNEY_APPROVAL` ⇒ Resolve ⇒ asserts document returns to `AWAITING_APPROVAL` at Attorney Approval (per C4-R6 origin restoration) ⇒ approve through to `FILED`.

### 6.5 Coverage thresholds (Pass 5 resolution)

`vitest.config.ts`:

```
coverage: { thresholds: { lines: 70, branches: 60, functions: 70, statements: 70 } }
```

Replaces the `0` placeholder C7 commits initially.

---

## 7. Error handling and edge cases

| Case | Behavior |
|---|---|
| **SSE drops** | Native `EventSource` reconnects automatically with the server-supplied `retry: 5000`. On reconnect, the next REST refetch of `['dashboard', orgId]` rehydrates state. No client-side `Last-Event-ID` (out of scope per C5-R8). No reconnect UI. |
| **Network failure on REST** | TanStack Query's default retry (3x with exponential backoff). On final failure, `useQuery.error` is rendered as an inline error message in the affected section ("Couldn't load documents — retry") with a retry button that calls `refetch()`. |
| **C5 returns `500 INTERNAL_ERROR`** (catch-all; includes stale-edit optimistic-lock overflow per C4 §7) | The action mutation's `onError` shows a generic toast ("Save failed; refresh and retry"), invalidates `['document', id]`, and leaves the form mounted. No automatic re-submit. Stale-edit collisions are not distinguished from other internal errors — the take-home has no concurrent editors. |
| **C5 returns `400 VALIDATION_FAILED` on action** | Inline field errors (mapped from response body) on the form. |
| **`reextractionStatus = FAILED`** | Banner visible (AC6.3); form re-enabled with prior values. Recovery is selecting a doc-type again, which re-opens the reclassify modal and re-fires the retype mutation. No retry button. |
| **`ProcessingDocument.currentStep = FAILED`** | Inline failure on the row (AC3.2). No retry affordance. Recovery is re-uploading the original file, which produces a new `ProcessingDocument` row. |
| **Rejected terminal state** | Stage progress per AC7.3: red incoming segment, red `Rejected` node, muted never-reached approvals. Form is read-only; only `Back to Documents` is shown. |
| **Empty filter result** | Documents section renders an empty-state row ("No documents match these filters"). Stats bar is unaffected (totals are unfiltered). |
| **Unsupported file at upload** | Server returns 415; show toast with server message; processing row is rolled back via TanStack Query's `onError`. |
| **PDF fails to render** | `react-pdf`'s `onLoadError` shows a fallback message in the left panel; the form panel remains usable. |

---

## 8. Migration / backwards compatibility

N/A. Greenfield component.

---

## 9. Decisions, contradictions, and open items

### 9.1 Decisions made (where research left options open)

- **Frontend coverage threshold:** **70% line / 60% branch** (also 70% functions, 70% statements). Resolves the placeholder carried by C7-R6a.
- **SSE consumer pattern:** refetch-on-any-event for both dashboard and detail. C6-R5 requires this; the research draft sketched surgical `setQueryData` updates, but the spec text wins.
- **No `['processing', id]` query.** The research draft included it, but C6-R7 forbids a `ProcessingDocument` detail route. The dashboard query is the only owner of in-flight state.
- **No optimistic update on action mutations** (Approve/Reject/Flag/Resolve/Retype). Only upload uses optimistic UI. Stage transitions are infrequent and a brief disabled-button window is preferable to rollback complexity.
- **Hook name** `useOrgEvents` (not `useDocumentEvents`) — research noted the rename; adopted here.
- **Selected org lives in URL** (`/org/:orgId/...`), not in localStorage. C6-R1 says "stores the selection client-side" — URL counts as client-side and is bookmarkable.
- **Coverage gate is enforced** in `vitest.config.ts`, not just reported. CI fails below threshold.

### 9.2 Contradictions surfaced (forwarded to the cross-spec review)

1. **SSE event vocabulary mismatch.** The C5 cross-component table commits to exactly two SSE events: `ProcessingStepChanged` and `DocumentStateChanged` (with `reextractionStatus` carried in the latter's payload). The C6 research draft (§5) sketched seven event types including `ProcessingFailed`, `ProcessingCompleted`, and three `DocumentReextraction*` events. **C6 spec follows C5's two-event contract.** The C5 spec must confirm that retype start/finish/failure are signaled via `DocumentStateChanged.reextractionStatus` transitions; if C5 instead emits dedicated reextraction events, the `useOrgEvents` listener list expands accordingly.
2. **Dashboard read-model shape.** C6-R2 reads `{ stats, processing, documents }` from one endpoint. The C5 cross-component summary describes `{ processing, documents }` only — `stats` is implied by C6-R2 but not made explicit in the C5 producer row. **C6 spec assumes `stats` is part of the response payload** with keys `inProgress`, `awaitingReview`, `flagged`, `filedThisMonth`. C5's spec must either confirm or relocate stats to a separate endpoint (in which case DashboardPage runs two queries and the SSE invalidation list expands).
3. **`ProcessingStepChanged` payload.** C6 currently treats it as a "refetch dashboard" trigger (no fields needed). If C5 carries `processingDocumentId` and the dashboard list is keyed by that id (rather than by document filename), this is fine. If C5 carries `documentId` instead (post-completion materialization), the listener may need to call `refetchDocument` too. Flagged for cross-spec review.

### 9.3 Routine items resolved

- Coverage threshold (above).
- Hook naming convention (`useOrgEvents`).
- E2E location (`frontend/e2e/`, separate from Vitest's `frontend/tests/`).
- MCP recipe lives in `AGENTS.md`, not `README.md` (no production caveats per memory).

### 9.4 Routine items still open

- None for C6 specifically. PDF.js worker version pinning happens at C7's `package.json` step and is verified by the build.
